package it.polito.cpo.service

import io.r2dbc.postgresql.codec.Json
import tools.jackson.databind.ObjectMapper
import it.polito.cpo.client.AuthorizationOutcome
import it.polito.cpo.client.CatalogClient
import it.polito.cpo.client.ConfirmReservationOutcome
import it.polito.cpo.client.HoldOutcome
import it.polito.cpo.client.InventoryClient
import it.polito.cpo.client.InventoryRefusalCode
import it.polito.cpo.client.OfferOutcome
import it.polito.cpo.client.PaymentClient
import it.polito.cpo.client.PaymentWindowOutcome
import it.polito.cpo.client.ResolvedOffer
import it.polito.cpo.client.ReversalOutcome
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.controller.dtos.CheckoutRequest
import it.polito.cpo.controller.dtos.CheckoutResponse
import it.polito.cpo.contracts.events.NotificationRequestedEvent
import it.polito.cpo.contracts.events.NotificationRequestedPayload
import it.polito.cpo.contracts.events.NotificationType
import it.polito.cpo.contracts.events.OrderConfirmedEvent
import it.polito.cpo.contracts.events.OrderPlacedEvent
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.OutboxEvent
import it.polito.cpo.observability.ApiException
import it.polito.cpo.observability.CorrelationId
import it.polito.cpo.repository.OutboxEventRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import it.polito.cpo.config.CheckoutMetrics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Checkout money-path coordinator.
 *
 * The synchronous entry ([checkout]) accepts the request quickly (202), and — in a single DB
 * transaction — persists the order in ACCEPTED, records the caller idempotency record, and writes the
 * `order-placed` outbox row. Debezium relays that outbox row to Kafka, so the DB write and the event
 * are atomic (no dual-write). The reserve -> pay -> confirm pipeline is NOT run inline: it is driven
 * by the `order-placed` Kafka consumer ([processOrderPlaced]) so it survives restarts.
 *
 * Under at-least-once delivery the pipeline is idempotent because it **dispatches on the durable
 * order status**, not on where a previous attempt got to in memory. Each phase has exactly one resume
 * action (docs/design/reservation-confirmation.md:138-144) and the `when` in [runStage] is exhaustive,
 * so adding a state cannot compile until its resume action is defined.
 *
 * The single most important property here is that an **unknown remote outcome never becomes a business
 * decision** (invariant 8, doc:92). There is deliberately no `catch` around the remote calls: the
 * clients return sealed outcomes for definitive results only and throw everything else, so a timeout,
 * a 5xx or an open circuit propagates to the Kafka listener, the offset is not committed, and the
 * durable phase is left exactly as it was for the next delivery to resume.
 */
@Service
class CheckoutOrchestrator(
    private val orderService: OrderService,
    private val inventoryClient: InventoryClient,
    private val paymentClient: PaymentClient,
    private val catalogClient: CatalogClient,
    private val outboxEventRepository: OutboxEventRepository,
    private val reconciliationService: ReconciliationService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val txOperator: TransactionalOperator,
    private val orderOutbox: OrderOutbox,
) {
    private val logger = LoggerFactory.getLogger(CheckoutOrchestrator::class.java)

    /**
     * Registers every checkout-outcome series at zero before the first checkout.
     *
     * The canary's checkout gates take `increase()` of `orders.checkout.completed{result}` and
     * `orders.checkout.failed{reason}`, filtered by pod. A series created by its first increment is
     * first scraped at 1, and `increase()` — which needs an earlier sample — reports 0, so on a fresh
     * canary pod the first checkout of each kind would vanish: the first failure from the failure-rate
     * gate, one sample from the sample-count gate. Same fix as the dead-letter counter
     * (`registerExhaustedSeries`), for the same reason.
     */
    init {
        listOf("confirmed", "failed").forEach { meterRegistry.counter("orders.checkout.completed", "result", it) }
        FailureClassifier.CHECKOUT_FAILURE_REASONS.forEach {
            meterRegistry.counter("orders.checkout.failed", "reason", it)
        }
    }

    private companion object {
        /**
         * ACCEPTED -> RESERVING -> PAYMENT_PENDING -> CONFIRMING_INVENTORY -> COMPENSATING -> FAILED
         * is the longest path. A backstop only: every stage is asserted to advance the state and the
         * graph is acyclic, so exceeding this is a programming error, and turning it into a loud
         * exception beats spinning on the consumer thread.
         */
        const val MAX_STEPS = 6

        /**
         * The shape Payments accepts, mirrored here so a malformed one is caught before it is
         * persisted. Kept as a shape check and not a list of known codes: Orders has no business
         * deciding which currencies exist.
         */
        val ISO_CURRENCY = Regex("^[A-Z]{3}$")

        /**
         * Stands in the fingerprint for the token a customer-hold checkout does not carry (ADR-016).
         * Angle brackets cannot occur in a provider token, so no real token can collide with it, and
         * a checkout that does carry one keeps exactly the canonical string it always had.
         */
        const val NO_TOKEN_MARKER = "<payment-deferred>"

        /** How many expired holds the T1 job closes per run. */
        const val HOLD_EXPIRY_BATCH = 100

        /** The audit actor for a transition no person asked for: the T1 expiry job. */
        const val SYSTEM_ACTOR = "system:hold-expiry"
    }

    /** One delivery's working state. [reservationId] is seeded from the row, so a resume needs no lookup. */
    private class PipelineContext(val order: Order, val correlationId: String) {
        val orderId: UUID get() = order.id
        var reservationId: String? = order.reservationId
        var compensationReason: String? = order.compensationReason
        var paymentWindowOpened: Boolean = order.paymentWindowOpened
    }

    /**
     * The outcome of one stage. Anything not representable here is an exception, which is what keeps
     * unknown outcomes out of the control flow.
     */
    private sealed interface Step {
        /** A durable transition committed; keep dispatching on [next]. */
        data class Advance(val next: OrderStatus) : Step

        /**
         * Durable state intentionally left as is. Stop and acknowledge the delivery. [expected] marks
         * a park that is the normal flow (a customer-hold order waiting to be paid) rather than a
         * problem, so it is not logged as a warning.
         */
        data class Park(val state: OrderStatus, val reason: String, val expected: Boolean = false) : Step
    }

    suspend fun checkout(
        request: CheckoutRequest,
        idempotencyKey: String,
        userId: String,
        recipientEmail: String?,
    ): CheckoutResponse {
        // Reuse the correlation id resolved by the inbound WebFilter (falls back to a fresh one
        // when the call is not driven through the filter, e.g. unit tests) so the trace stays
        // consistent across the sync accept, the emitted event, and the async pipeline.
        val correlationId = currentCorrelationId()

        // Every refusal below is an ApiException, so one catch audits all of them — the guards, the
        // idempotency conflict and each way the offer can fail to resolve — without an order id,
        // since none exists yet.
        try {
            return accept(request, idempotencyKey, userId, recipientEmail, correlationId)
        } catch (e: ApiException) {
            MoneyPathAudit.record(
                MoneyPathAudit.Action.CHECKOUT, MoneyPathAudit.Outcome.REJECTED, userId,
                orderId = null, correlationId = correlationId, reason = e.code,
            )
            throw e
        }
    }

    private suspend fun accept(
        request: CheckoutRequest,
        idempotencyKey: String,
        userId: String,
        recipientEmail: String?,
        correlationId: String,
    ): CheckoutResponse {
        // Syntactic guard only, and deliberately so. Which dates actually have seats is Inventory's
        // to know (ADR-001), and a date with none becomes a definitive hold rejection the pipeline
        // already handles. Orders checks just this much because, unlike routeId — picked from a list —
        // the date is entered by the customer, so a mistake is likely; without the guard they would
        // only find out from a FAILED order well after the 202.
        if (request.travelDate.isBefore(LocalDate.now())) {
            meterRegistry.counter("orders.checkout.rejected", "reason", "travel_date_in_the_past").increment()
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "TRAVEL_DATE_IN_THE_PAST",
                "Travel date ${request.travelDate} is in the past",
            )
        }

        // Nothing downstream can price a checkout with no seats, and once the expected total is
        // `price × seats.size` an empty list would make it zero — so a request carrying `0` would
        // pass the price check that exists to stop exactly that. Refused before resolution, because
        // it is wrong whatever the offer says.
        if (request.seats.isEmpty()) {
            meterRegistry.counter("orders.checkout.rejected", "reason", "seats_required").increment()
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "SEATS_REQUIRED",
                "A checkout must carry at least one seat",
            )
        }

        val fingerprint = fingerprint(userId, request)

        // 1. Check idempotency. Same key + same payload -> replay the stored result; same key + a
        //    different logical payload -> 409 Conflict (api-design.md §Shared Request Headers).
        val existing = orderService.findIdempotentRequest(idempotencyKey)
        if (existing != null) {
            if (existing.requestFingerprint != null && existing.requestFingerprint != fingerprint) {
                meterRegistry.counter("orders.checkout.idempotency_conflict").increment()
                logger.warn("Idempotency conflict for key {}: fingerprint mismatch", idempotencyKey)
                throw ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency key was reused with a different request",
                )
            }
            meterRegistry.counter("orders.checkout.idempotent_replay").increment()
            logger.info("Found existing request for idempotency key: {}", idempotencyKey)
            val replayed = objectMapper.readValue(existing.responseBody, CheckoutResponse::class.java)
            MoneyPathAudit.record(
                MoneyPathAudit.Action.CHECKOUT, MoneyPathAudit.Outcome.REPLAYED, userId,
                replayed.orderId, correlationId,
            )
            return replayed
        }

        // 2. Resolve the offer against Catalog, which is what makes the amount below trustworthy
        //    (issue #93). Deliberately after the idempotency check: a replay must return the stored
        //    result from the original decision, and re-resolving would make an accepted order
        //    unreplayable the moment Catalog went down or its price changed.
        val offer = resolveOffer(request.offerId, request)

        meterRegistry.counter("orders.checkout.started").increment()

        // 3. Create order in ACCEPTED status (keep the payment token so the async pipeline can resume)
        val orderId = UUID.randomUUID()
        val order = Order(
            id = orderId,
            userId = userId,
            status = OrderStatus.ACCEPTED,
            routeId = request.routeId,
            seats = request.seats.joinToString(","),
            travelDate = request.travelDate,
            // The resolved line total, not the requested one. They are equal in value — a mismatch
            // was rejected above — but only this one is derived from Catalog's price, and persisting
            // the request's number would leave the authorization sourced from the client after all.
            totalAmount = offer.price.multiply(BigDecimal(request.seats.size)),
            currency = offer.currency,
            createdAt = LocalDateTime.now(),
            paymentMethodToken = request.paymentMethodToken,
            paymentDeferred = request.paymentMethodToken == null,
            recipientEmail = recipientEmail,
            offerId = request.offerId,
            // The refund terms as they read at the moment of sale, frozen because Catalog keeps no
            // history of them (ADR-015 and V14).
            refundable = offer.refundPolicy.refundable,
            refundCutoffMinutesBeforeDeparture = offer.refundPolicy.cutoffMinutesBeforeDeparture,
            offerTermsVersion = offer.termsVersion,
            departureAt = offer.departureAt(request.travelDate),
        ).apply { setAsNew(true) }

        // 4. Persist order + idempotency + the order-placed outbox row atomically (fixes the
        //    order/idempotency and order/Kafka dual-write). Debezium relays the outbox row.
        val response = CheckoutResponse(orderId, OrderStatus.ACCEPTED)
        val responseBody = objectMapper.writeValueAsString(response)
        try {
            txOperator.executeAndAwait {
                orderService.saveOrder(order)
                orderService.saveIdempotentRequest(idempotencyKey, responseBody, userId, "checkout", fingerprint)
                outboxEventRepository.save(
                    outbox(
                        "order-placed", orderId,
                        OrderPlacedEvent(correlationId = correlationId, orderId = orderId, principalId = userId),
                    )
                )
            }
        } catch (duplicate: DuplicateKeyException) {
            return afterDuplicate(duplicate, order, idempotencyKey, fingerprint, userId, correlationId)
        }

        MoneyPathAudit.record(
            MoneyPathAudit.Action.CHECKOUT, MoneyPathAudit.Outcome.ACCEPTED, userId, orderId, correlationId,
        )
        return response
    }

    /**
     * The checkout insert hit a unique constraint, and the whole transaction rolled back. Two
     * constraints can do that, and they need opposite answers.
     *
     * The idempotency key is checked first: a concurrent retry of the same request lost the race on
     * the key, and it must replay the winner's response — not be told it has another hold, which the
     * winner's own order would otherwise make it look like. Only then the one-hold-per-customer index
     * (V19, invariant 11): the customer already has a customer-hold order waiting, and the answer
     * points at it so the client can resume or cancel it.
     */
    private suspend fun afterDuplicate(
        duplicate: DuplicateKeyException,
        order: Order,
        idempotencyKey: String,
        fingerprint: String,
        userId: String,
        correlationId: String,
    ): CheckoutResponse {
        orderService.findIdempotentRequest(idempotencyKey)?.let { existing ->
            if (existing.requestFingerprint == fingerprint) {
                meterRegistry.counter("orders.checkout.idempotent_replay").increment()
                val replayed = objectMapper.readValue(existing.responseBody, CheckoutResponse::class.java)
                MoneyPathAudit.record(
                    MoneyPathAudit.Action.CHECKOUT, MoneyPathAudit.Outcome.REPLAYED, userId,
                    replayed.orderId, correlationId,
                )
                return replayed
            }
            meterRegistry.counter("orders.checkout.idempotency_conflict").increment()
            throw ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "Idempotency key was reused with a different request",
            )
        }
        // A direct-flow order is not counted by the index, so for it the duplicate cannot be the hold.
        if (!order.paymentDeferred) throw duplicate
        meterRegistry.counter("orders.checkout.rejected", "reason", "active_hold_exists").increment()
        // The blocking hold can end between the failed insert and this read (paid, cancelled, expired).
        // Then there is nothing to point at, and the honest answer is the same conflict with no order:
        // the customer simply tries again, and this time the insert passes. Not a raw 500.
        val active = orderService.findActiveHold(userId)
            ?: throw ApiException(
                HttpStatus.CONFLICT,
                "ACTIVE_HOLD_EXISTS",
                "Your previous held checkout has just ended. Please try again.",
                mapOf("orderId" to null),
            )
        throw ApiException(
            HttpStatus.CONFLICT,
            "ACTIVE_HOLD_EXISTS",
            "You already have seats held for another checkout. Resume it or cancel it first.",
            mapOf("orderId" to active.id),
        )
    }

    /**
     * The customer-hold payment (ADR-016): accepts the token for an order parked in
     * HELD_AWAITING_PAYMENT, while its T1 deadline has not passed by the database clock.
     *
     * One transaction, so the durable trigger cannot be lost: the compare-and-set to PAYMENT_PENDING
     * (which also stores the token and the submission time), the idempotency record, and a new
     * `order-placed` outbox row. The pipeline resumes from PAYMENT_PENDING on that row — opening the
     * payment window first — so a crash after this commit needs no browser to recover.
     *
     * Ownership is the controller's; [userId] is the owner, already checked.
     */
    suspend fun submitPayment(
        orderId: UUID,
        paymentMethodToken: String,
        idempotencyKey: String,
        userId: String,
    ): CheckoutResponse {
        val correlationId = currentCorrelationId()
        try {
            return acceptPayment(orderId, paymentMethodToken, idempotencyKey, userId, correlationId)
        } catch (e: ApiException) {
            MoneyPathAudit.record(
                MoneyPathAudit.Action.PAYMENT_SUBMIT, MoneyPathAudit.Outcome.REJECTED, userId,
                orderId, correlationId, reason = e.code,
            )
            throw e
        }
    }

    private suspend fun acceptPayment(
        orderId: UUID,
        paymentMethodToken: String,
        idempotencyKey: String,
        userId: String,
        correlationId: String,
    ): CheckoutResponse {
        val fingerprint = paymentFingerprint(userId, orderId, paymentMethodToken)
        replayPayment(idempotencyKey, fingerprint, userId, orderId, correlationId)?.let { return it }

        val response = CheckoutResponse(orderId, OrderStatus.PAYMENT_PENDING)
        val responseBody = objectMapper.writeValueAsString(response)
        val accepted = try {
            txOperator.executeAndAwait {
                if (orderService.submitPayment(orderId, paymentMethodToken) == 0L) {
                    false
                } else {
                    orderService.saveIdempotentRequest(idempotencyKey, responseBody, userId, "payment", fingerprint)
                    outboxEventRepository.save(
                        outbox(
                            "order-placed", orderId,
                            OrderPlacedEvent(correlationId = correlationId, orderId = orderId, principalId = userId),
                        )
                    )
                    true
                }
            }
        } catch (_: DuplicateKeyException) {
            // The key was taken in between: by a concurrent retry of this very request (replay it), or
            // by a different one (conflict). Either way the replay check below settles it.
            false
        }

        if (accepted != true) {
            replayPayment(idempotencyKey, fingerprint, userId, orderId, correlationId)?.let { return it }
            throw paymentRefusal(orderId)
        }

        meterRegistry.counter("orders.payment.submitted", "result", "accepted").increment()
        MoneyPathAudit.record(
            MoneyPathAudit.Action.PAYMENT_SUBMIT, MoneyPathAudit.Outcome.ACCEPTED, userId, orderId, correlationId,
        )
        return response
    }

    /** The stored response for a retried submission, a conflict for a reused key, or null for a new one. */
    private suspend fun replayPayment(
        idempotencyKey: String,
        fingerprint: String,
        userId: String,
        orderId: UUID,
        correlationId: String,
    ): CheckoutResponse? {
        val existing = orderService.findIdempotentRequest(idempotencyKey) ?: return null
        if (existing.requestFingerprint != fingerprint) {
            meterRegistry.counter("orders.payment.submitted", "result", "idempotency_conflict").increment()
            throw ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "Idempotency key was reused with a different request",
            )
        }
        meterRegistry.counter("orders.payment.submitted", "result", "replayed").increment()
        MoneyPathAudit.record(
            MoneyPathAudit.Action.PAYMENT_SUBMIT, MoneyPathAudit.Outcome.REPLAYED, userId, orderId, correlationId,
        )
        return objectMapper.readValue(existing.responseBody, CheckoutResponse::class.java)
    }

    /**
     * Why the compare-and-set matched nothing, read back from the row. Read after the fact, so it
     * describes the state that won, never a guess made before the race.
     */
    private suspend fun paymentRefusal(orderId: UUID): ApiException {
        val order = orderService.getOrderById(orderId)
            ?: return ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")
        // A hold past T1 is the same answer whether the expiry job has already cancelled the order or not
        // yet: the job runs every few seconds, and the customer's situation is identical either way.
        // The JVM clock only picks the wording here; the decision was the compare-and-set's.
        val expiredHold = order.paymentDeferred &&
            order.reservationExpiresAt?.isAfter(OffsetDateTime.now()) == false &&
            (order.status == OrderStatus.HELD_AWAITING_PAYMENT || order.status == OrderStatus.CANCELLED)
        val (code, message) = when {
            order.status == OrderStatus.HELD_AWAITING_PAYMENT || expiredHold ->
                "HOLD_EXPIRED" to "The time to pay for these seats has run out. Please choose your seats again."
            order.paymentDeferred && order.paymentSubmittedAt != null ->
                "PAYMENT_ALREADY_STARTED" to "A payment for this order has already been accepted."
            else ->
                "ORDER_NOT_PAYABLE" to "Order in state ${order.status} does not accept a payment."
        }
        meterRegistry.counter("orders.payment.submitted", "result", code.lowercase()).increment()
        return ApiException(HttpStatus.CONFLICT, code, message)
    }

    // Same construction as the checkout fingerprint. The operation is part of the canonical string
    // because the idempotency table is keyed by the key alone: a client reusing its checkout key here
    // must get a conflict, not the checkout's stored response.
    private fun paymentFingerprint(userId: String, orderId: UUID, paymentMethodToken: String): String {
        val canonical = "payment|$userId|$orderId|$paymentMethodToken"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Resolves [offerId] against Catalog and checks the request against it, or rejects the checkout.
     *
     * Three things are verified, and each answers a different question: that the offer **exists**,
     * that it belongs to the **route** being booked, and that its **price** is the amount the client
     * asked to be charged. Until this existed, `totalAmount` arrived from the browser and was
     * validated against nothing — a checkout submitted with `0.01` was authorized for one cent and
     * confirmed as a normal sale, because Payments authorizes what it is given and Inventory has no
     * notion of price (issue #93).
     *
     * A mismatch is a `400`, never a silent correction to the true price: charging an amount other
     * than the one the customer was shown is its own bug, and a worse one than refusing the request.
     *
     * Synchronous and fail-closed by design. The alternative placements are both worse: validating
     * inside the pipeline would turn a fixable mistake into a `FAILED` order discovered minutes
     * later, and trusting the client's amount when Catalog is unreachable would reopen #93 precisely
     * when nobody is watching. The cost is stated plainly — while Catalog is down, checkouts
     * carrying an offer are refused — and it is a `503`, which says "ask again" rather than "you are
     * wrong".
     */
    private suspend fun resolveOffer(offerId: String, request: CheckoutRequest): ResolvedOffer {
        val outcome = try {
            catalogClient.getOffer(offerId)
        } catch (cancellation: CancellationException) {
            // A disconnected client is not a Catalog failure; swallowing it into a 503 would report a
            // dependency problem that never happened.
            throw cancellation
        } catch (failure: Exception) {
            meterRegistry.counter("orders.checkout.rejected", "reason", "offer_unresolvable").increment()
            logger.warn("Catalog could not resolve offer {}; refusing the checkout", offerId, failure)
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "OFFER_UNRESOLVABLE",
                "The offer could not be verified right now. Please try again.",
                cause = failure,
            )
        }

        val offer = when (outcome) {
            is OfferOutcome.Found -> outcome.offer
            OfferOutcome.NotFound -> reject(
                "OFFER_NOT_FOUND",
                "Offer $offerId does not exist",
                mapOf("offerId" to offerId),
            )
        }

        // Is this offer usable at all? Checked **before** the two comparisons below, and the order
        // is the point: a garbage price compared against the request would come back as
        // OFFER_PRICE_MISMATCH, a 400 blaming the customer for a broken catalogue.
        //
        // These two fields are checked and no others because these two leave Orders and enter
        // fields Payments validates: `PaymentService` rejects a non-ISO-4217 currency and a
        // non-positive amount with a 400, and `WebClientPaymentClient` treats only DECLINED as
        // definitive — so that 400 becomes an *unknown* outcome. The order would sit in
        // PAYMENT_PENDING holding a seat while the delivery is retried to the dead-letter topic: a
        // poison message rather than a clean failure, and the poison would be catalogue data.
        // Refusing here turns it into one rejected checkout.
        //
        // Unreachable today — the six seeded offers are compile-time constants — and the path is
        // new: this used to be a literal "EUR" and a client-supplied number, so it could not be
        // malformed, only wrong.
        if (!ISO_CURRENCY.matches(offer.currency) || offer.price <= BigDecimal.ZERO) {
            // Deliberately the same public code as an unreachable Catalog. To a caller both mean
            // "no trustworthy offer could be obtained", and a fifth code added to an error model
            // api-design.md calls frozen is not worth a case that needs someone to ship a malformed
            // offer first. The two are told apart where an operator actually needs it: `error`
            // rather than `warn`, and a different metric tag.
            meterRegistry.counter("orders.checkout.rejected", "reason", "offer_malformed").increment()
            logger.error(
                "Catalog returned an unusable offer {}: price={} currency={}",
                offerId, offer.price, offer.currency,
            )
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "OFFER_UNRESOLVABLE",
                "The offer could not be verified right now. Please try again.",
            )
        }

        if (offer.routeId != request.routeId) {
            // Cheap to get wrong in a client that keeps the offer across a route change, and
            // expensive to leave: the seats would be held on one route and the refund terms taken
            // from an offer for another.
            reject(
                "OFFER_ROUTE_MISMATCH",
                "Offer $offerId is not sold on route ${request.routeId}",
                mapOf("offerId" to offerId, "routeId" to offer.routeId),
            )
        }

        // An offer price is per seat, so the amount to compare is the line total. Getting this wrong
        // is not a rounding detail: comparing the unit price against the total rejected every
        // checkout for two or more seats, and the single-seat tests could not see it because at a
        // quantity of one the two are the same number.
        val expectedTotal = offer.price.multiply(BigDecimal(request.seats.size))

        // compareTo, not equals: BigDecimal("24.90") != BigDecimal("24.9") by equals, and rejecting a
        // correct amount because the client sent one fewer trailing zero would be a bug of its own.
        if (expectedTotal.compareTo(request.totalAmount) != 0) {
            reject(
                "OFFER_PRICE_MISMATCH",
                "Offer $offerId costs ${offer.price} ${offer.currency} per seat, " +
                    "so ${request.seats.size} seats cost $expectedTotal, not ${request.totalAmount}",
                // Catalog data, already public on the offers endpoint: telling the client the real
                // price lets it re-render rather than guess. `expectedTotal` is what the client got
                // wrong; `price` is what it needs to recompute from.
                mapOf(
                    "offerId" to offerId,
                    "price" to offer.price,
                    "currency" to offer.currency,
                    "expectedTotal" to expectedTotal,
                ),
            )
        }

        return offer
    }

    /** A `400` on the checkout entry, counted under the same rejection meter as the date guard. */
    private fun reject(code: String, message: String, details: Map<String, Any?>): Nothing {
        meterRegistry.counter("orders.checkout.rejected", "reason", code.lowercase()).increment()
        throw ApiException(HttpStatus.BAD_REQUEST, code, message, details)
    }

    // Canonical, order-independent fingerprint of the checkout payload (SHA-256 hex). Used to tell a
    // genuine retry (same payload -> replay) from a key reuse with a different payload (-> 409).
    //
    // It hashes what the client *asked for* — the requested amount included — and deliberately not
    // the price resolved from Catalog. The resolved price is an output of the request, so feeding it
    // back in would make a retry of an unchanged payload hash differently after a catalogue edit.
    private fun fingerprint(userId: String, request: CheckoutRequest): String {
        val canonical = buildString {
            append(userId).append('|')
            append(request.routeId).append('|')
            append(request.seats.sorted().joinToString(",")).append('|')
            // The travel date is part of what was ordered, so it must be part of the fingerprint.
            // Omit it and the same idempotency key reused for a different day would look like a
            // genuine retry: the caller would get a 202 replaying the *other* day's order and
            // believe it had booked the day it asked for.
            append(request.travelDate).append('|')
            append(request.totalAmount.stripTrailingZeros().toPlainString()).append('|')
            append(request.paymentMethodToken ?: NO_TOKEN_MARKER)
            // Unconditional now that the field is required, which also removes the collision the
            // conditional form carried: with a variable number of separators, a paymentMethodToken
            // of `tok|of-001` with no offer hashed identically to the token `tok` with offer
            // `of-001`. Every canonical string now has fixed arity, so that cannot happen.
            //
            // This had to wait for the required field and could not land a day earlier: changing the
            // canonical string while the offer was optional would have made every idempotency key
            // stored in the previous 24 hours collide with its own stored fingerprint and answer 409
            // to its own retry.
            append('|').append(request.offerId)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Entry point for the `order-placed` consumer. Reads the durable state fresh, so a redelivery
     * resumes rather than restarting.
     */
    suspend fun processOrderPlaced(event: OrderPlacedEvent) {
        val order = orderService.getOrderById(event.orderId)
        if (order == null) {
            logger.warn("Received order-placed for unknown order: {}", event.orderId)
            return
        }
        if (order.status.isTerminal) {
            // doc:144 — acknowledge the duplicate without making any remote call.
            meterRegistry.counter("orders.checkout.duplicate", "state", order.status.name).increment()
            logger.info("Skipping order-placed for order {} already terminal in {}", event.orderId, order.status)
            return
        }
        // A customer-hold order's first delivery after the payment finds PAYMENT_PENDING with no window
        // yet: that is its normal start, not a resume, and counting it would inflate the redelivery
        // proxy by one per paid order.
        val paymentStart = order.status == OrderStatus.PAYMENT_PENDING && order.paymentDeferred && !order.paymentWindowOpened
        if (order.status != OrderStatus.ACCEPTED && !paymentStart) {
            meterRegistry.counter("orders.checkout.resumed", "state", order.status.name).increment()
            logger.info("Resuming checkout for order {} from {}", event.orderId, order.status)
        }
        runPipeline(PipelineContext(order, event.correlationId))
    }

    private suspend fun runPipeline(ctx: PipelineContext) {
        val sample = Timer.start(meterRegistry)
        val entryState = ctx.order.status
        var state = entryState
        var steps = 0
        logger.info("Running checkout pipeline for order {} from {}", ctx.orderId, entryState)
        try {
            while (!state.isTerminal) {
                check(steps++ < MAX_STEPS) {
                    "Checkout pipeline for order ${ctx.orderId} exceeded $MAX_STEPS steps at $state"
                }
                when (val step = runStage(state, ctx)) {
                    is Step.Park -> {
                        if (step.expected) {
                            logger.info("Order {} parked in {}: {}", ctx.orderId, step.state, step.reason)
                        } else {
                            logger.warn("Order {} parked in {}: {}", ctx.orderId, step.state, step.reason)
                        }
                        state = step.state
                        break
                    }
                    is Step.Advance -> {
                        // A stage must never return its own input state. With an acyclic state graph
                        // that is what makes this loop provably terminating.
                        check(step.next != state) { "Stage $state did not advance order ${ctx.orderId}" }
                        state = step.next
                    }
                }
            }
        } finally {
            sample.stop(
                meterRegistry.timer(
                    "orders.checkout.duration",
                    "outcome", state.name.lowercase(),
                    "entry_state", entryState.name.lowercase(),
                ),
            )
        }
    }

    private suspend fun runStage(state: OrderStatus, ctx: PipelineContext): Step = when (state) {
        // doc:140 — ACCEPTED and RESERVING share one resume action: repeat the idempotent hold.
        OrderStatus.ACCEPTED, OrderStatus.RESERVING -> stageHold(ctx)
        // ADR-016: the customer has the seats and has not paid. Only the payment command, a
        // cancellation or the T1 expiry job move the order on, so a redelivery does nothing at all.
        OrderStatus.HELD_AWAITING_PAYMENT ->
            Step.Park(OrderStatus.HELD_AWAITING_PAYMENT, "waiting for the customer's payment", expected = true)
        OrderStatus.PAYMENT_PENDING -> stagePaymentWindowThenAuthorize(ctx)
        OrderStatus.CONFIRMING_INVENTORY -> stageConfirmInventory(ctx)
        OrderStatus.COMPENSATING -> stageCompensate(ctx)
        // REFUNDING/REFUNDED are ADR-015's own process, never entered by this pipeline's own
        // transitions (only the refund endpoint moves CONFIRMED -> REFUNDING), and both answer
        // `true` from OrderStatus.isTerminal, so the loop above never dispatches here with them
        // either. Listed only because `when` over an enum must be exhaustive; this arm can never run.
        OrderStatus.CONFIRMED, OrderStatus.FAILED, OrderStatus.CANCELLED,
        OrderStatus.REFUNDING, OrderStatus.REFUNDED,
        -> error("unreachable: terminal states are filtered by the loop")
    }

    // --- stages -----------------------------------------------------------------------------------

    private suspend fun stageHold(ctx: PipelineContext): Step {
        if (orderService.markReserving(ctx.orderId) == 0L) {
            logger.info("Order {} cancelled before the hold; skipping", ctx.orderId)
            return Step.Advance(OrderStatus.CANCELLED)
        }

        meterRegistry.counter("orders.inventory.hold.attempt").increment()
        val outcome = inventoryClient.reserveSeats(
            ReservationRequest(
                orderId = ctx.orderId,
                seats = ctx.order.seats.split(","),
                routeId = ctx.order.routeId,
                travelDate = ctx.order.travelDate,
            ),
            idempotencyKey = "inv-${ctx.orderId}",
            userId = ctx.order.userId,
        )

        return when (outcome) {
            is HoldOutcome.Rejected -> {
                countHold("rejected", outcome.code.name)
                // No money exists yet, so there is nothing to reverse and nothing to release.
                failUnpaid(ctx, FailureClassifier.forInventoryRefusal(outcome.code), releaseHold = false)
            }
            is HoldOutcome.Held -> {
                countHold("held", "none")
                ctx.reservationId = outcome.reservationId
                // The customer-hold flow stops here for the customer; the direct flow goes on to pay.
                val next = if (ctx.order.paymentDeferred) OrderStatus.HELD_AWAITING_PAYMENT else OrderStatus.PAYMENT_PENDING
                val marked = if (next == OrderStatus.HELD_AWAITING_PAYMENT) {
                    orderService.markHeldAwaitingPayment(ctx.orderId, outcome.reservationId, outcome.expiresAt)
                } else {
                    orderService.markPaymentPending(ctx.orderId, outcome.reservationId, outcome.expiresAt)
                }
                if (marked == 0L) {
                    // doc:298 — a cancellation won the race while the hold was in flight. The order is
                    // CANCELLED with no reservation id stored, so the pipeline is the only place that
                    // can release this hold.
                    logger.info("Order {} cancelled while the hold was in flight; releasing", ctx.orderId)
                    releaseHold(ctx, outcome.reservationId)
                    return Step.Advance(OrderStatus.CANCELLED)
                }
                Step.Advance(next)
            }
        }
    }

    /**
     * PAYMENT_PENDING. A customer-hold order whose payment window is not recorded yet asks Inventory
     * for it first; every other order — direct flow, or a window already recorded — authorizes.
     *
     * The recorded marker is what makes this safe on redelivery. Before it, Payments has never been
     * called, so a definitive refusal can fail the order with no reversal. After it, an authorization
     * may have been attempted with an unknown outcome, and asking for the window again could turn a
     * paid order into an "unpaid" failure — so it is never asked again (invariant 8).
     */
    private suspend fun stagePaymentWindowThenAuthorize(ctx: PipelineContext): Step {
        if (ctx.order.paymentDeferred && !ctx.paymentWindowOpened) {
            stagePaymentWindow(ctx)?.let { return it }
        }
        return stageAuthorize(ctx)
    }

    /** Opens the T2 window and records it, or ends the order. Null means: go on and authorize. */
    private suspend fun stagePaymentWindow(ctx: PipelineContext): Step? {
        val reservationId = ctx.reservationId
            ?: throw IllegalStateException("Order ${ctx.orderId}: PAYMENT_PENDING without a stored reservation id")

        // An unknown outcome throws out of here: PAYMENT_PENDING stays, the marker stays false, and
        // Payments is not called. A redelivery asks again and gets the same answer.
        return when (val outcome = inventoryClient.startPaymentWindow(reservationId, ctx.orderId)) {
            is PaymentWindowOutcome.Opened -> {
                if (orderService.markPaymentWindowOpened(ctx.orderId, reservationId, outcome.expiresAt) == 0L) {
                    // Cancellation is refused from PAYMENT_PENDING on, and an operator can abandon only
                    // past the hold, so nothing legitimate moves the row here. Unknown, not a decision.
                    throw IllegalStateException(
                        "Order ${ctx.orderId}: payment window opened but the order left PAYMENT_PENDING unexpectedly",
                    )
                }
                meterRegistry.counter("orders.inventory.payment_window", "result", "opened", "code", "none").increment()
                ctx.paymentWindowOpened = true
                null
            }
            is PaymentWindowOutcome.Refused -> {
                meterRegistry.counter(
                    "orders.inventory.payment_window", "result", "refused", "code", outcome.code.name,
                ).increment()
                val failureClass = FailureClassifier.forInventoryRefusal(outcome.code)
                if (outcome.code.isInvariantViolation) {
                    val record = reconciliationService.recordInvariantViolation(
                        orderId = ctx.orderId,
                        orderState = OrderStatus.PAYMENT_PENDING.name,
                        correlationId = ctx.correlationId,
                        failureClass = failureClass,
                        detail = "Inventory refused the payment window with ${outcome.code} for reservation $reservationId"
                            + (outcome.detail?.let { ": $it" } ?: ""),
                    )
                    reconciliationService.countRequired(record)
                }
                // No money exists: Payments has never been called for this order. No release either:
                // an expired hold was released by Inventory in the refusing transaction, a cancelled
                // one already was, and on a conflict or a missing reservation there is nothing of
                // ours to release.
                failUnpaid(ctx, failureClass, releaseHold = false)
            }
        }
    }

    private suspend fun stageAuthorize(ctx: PipelineContext): Step {
        val paymentToken = ctx.order.paymentMethodToken
        if (paymentToken == null) {
            logger.error("Order {} reached authorization without a payment token", ctx.orderId)
            return failUnpaid(ctx, FailureClassifier.PAYMENT_TOKEN_MISSING, releaseHold = true)
        }

        val outcome = paymentClient.authorizePayment(
            PaymentRequest(
                orderId = ctx.orderId,
                principalId = ctx.order.userId,
                // Both read off the order, which is where the checkout entry put the *resolved*
                // offer's price and currency. Read from the row rather than re-resolved here on
                // purpose: the pipeline is asynchronous and may resume days later, so a price edit
                // in Catalog — or Catalog simply being down — must not change what this order
                // authorizes. The currency used to be a hardcoded "EUR" on this line while the
                // amount came from the request, so the two were never checked against each other.
                //
                // Every order now carries a resolved offer, so both values are Catalog's. Orders
                // placed before `offerId` became required still hold what their client claimed —
                // there is no backfill, and no way to invent one.
                amount = ctx.order.totalAmount,
                currency = ctx.order.currency,
                paymentMethodToken = paymentToken,
            ),
            idempotencyKey = "pay-${ctx.orderId}",
            correlationId = ctx.correlationId,
        )

        return when (outcome) {
            is AuthorizationOutcome.Declined -> {
                meterRegistry.counter("orders.payment.authorize", "result", "declined").increment()
                // doc:299 — a definitive decline releases the hold and fails the order.
                failUnpaid(ctx, FailureClassifier.PAYMENT_DECLINED, releaseHold = true)
            }
            is AuthorizationOutcome.Authorized -> {
                meterRegistry.counter("orders.payment.authorize", "result", "authorized").increment()
                if (orderService.markConfirmingInventory(ctx.orderId) == 0L) {
                    // Cancellation is refused from PAYMENT_PENDING onward, so nothing legitimate can
                    // move the row here. Unknown, not a business failure: leave the money authorized
                    // and let redelivery converge.
                    throw IllegalStateException(
                        "Order ${ctx.orderId}: payment authorized but the order left PAYMENT_PENDING unexpectedly",
                    )
                }
                Step.Advance(OrderStatus.CONFIRMING_INVENTORY)
            }
        }
    }

    private suspend fun stageConfirmInventory(ctx: PipelineContext): Step {
        val reservationId = ctx.reservationId
            ?: throw IllegalStateException(
                "Order ${ctx.orderId}: CONFIRMING_INVENTORY without a stored reservation id",
            )

        meterRegistry.counter("orders.inventory.confirm.attempt").increment()
        // An unknown outcome throws out of here. The durable state stays CONFIRMING_INVENTORY, the
        // offset is not committed, and doc:303 ("do not refund or release") holds structurally
        // because there is no catch block to do it.
        val outcome = inventoryClient.confirmReservation(reservationId, ctx.orderId)

        return when (outcome) {
            is ConfirmReservationOutcome.Confirmed -> {
                countConfirm("confirmed", "none")
                commitConfirmed(ctx)
            }
            is ConfirmReservationOutcome.Refused -> {
                countConfirm("refused", outcome.code.name)
                enterCompensating(ctx, outcome.code, outcome.detail)
            }
        }
    }

    /**
     * doc:289-291 step 8: the order becomes CONFIRMED and BOTH final outbox rows are written in ONE
     * transaction, only after Inventory committed CONFIRMED + SOLD.
     *
     * Invariant 7 (`order-confirmed` only after the sale) holds structurally: this method is reachable
     * only from the [ConfirmReservationOutcome.Confirmed] arm above, and that arm has no edge to
     * compensation.
     */
    private suspend fun commitConfirmed(ctx: PipelineContext): Step {
        val committed = txOperator.executeAndAwait {
            if (orderService.markConfirmed(ctx.orderId) == 0L) {
                false
            } else {
                outboxEventRepository.save(
                    outbox(
                        "order-confirmed", ctx.orderId,
                        OrderConfirmedEvent(
                            correlationId = ctx.correlationId,
                            orderId = ctx.orderId,
                            principalId = ctx.order.userId,
                        ),
                    )
                )
                outboxEventRepository.save(
                    outbox(
                        "notification-requested", ctx.orderId,
                        NotificationRequestedEvent(
                            correlationId = ctx.correlationId,
                            orderId = ctx.orderId,
                            principalId = ctx.order.userId,
                            payload = NotificationRequestedPayload(
                                // No order id in the sentence: a UUID identifies the order to us and
                                // means nothing to the customer. The trip is what they recognise, and
                                // it travels as identifiers below for the consumer to resolve.
                                recipientEmail = recipientEmail(ctx.order),
                                message = "Your booking is confirmed.",
                                type = NotificationType.ORDER_CONFIRMED,
                                routeId = ctx.order.routeId,
                                travelDate = ctx.order.travelDate,
                            ),
                        ),
                    )
                )
                reconciliationService.closeAutoResolvable(ctx.orderId)
                true
            }
        }

        if (committed == true) {
            meterRegistry.counter("orders.checkout.completed", "result", "confirmed").increment()
            recordEndToEnd(ctx, "confirmed")
            MoneyPathAudit.record(
                MoneyPathAudit.Action.CHECKOUT_PIPELINE, MoneyPathAudit.Outcome.CONFIRMED,
                ctx.order.userId, ctx.orderId, ctx.correlationId,
            )
            logger.info("Successfully confirmed order {}", ctx.orderId)
            return Step.Advance(OrderStatus.CONFIRMED)
        }

        // markConfirmed has no self-transition, so 0 rows means either a concurrent delivery already
        // committed this order (idempotent: the outbox rows exist exactly once) or the row is in an
        // unexpected state, which is unknown and must be redriven rather than compensated.
        val current = orderService.getOrderById(ctx.orderId)?.status
        if (current == OrderStatus.CONFIRMED) {
            meterRegistry.counter("orders.checkout.duplicate", "state", OrderStatus.CONFIRMED.name).increment()
            return Step.Advance(OrderStatus.CONFIRMED)
        }
        throw IllegalStateException(
            "Order ${ctx.orderId}: Inventory confirmed the sale but the local commit affected 0 rows (state=$current)",
        )
    }

    /**
     * Moves into COMPENSATING, writing an INVARIANT_VIOLATION record in the SAME transaction when the
     * refusal describes a state that should not be reachable (doc:198-201).
     */
    private suspend fun enterCompensating(
        ctx: PipelineContext,
        code: InventoryRefusalCode,
        detail: String?,
    ): Step {
        val failureClass = FailureClassifier.forInventoryRefusal(code)
        val record = txOperator.executeAndAwait {
            if (orderService.markCompensating(ctx.orderId, code.name) == 0L) {
                null
            } else if (code.isInvariantViolation) {
                reconciliationService.recordInvariantViolation(
                    orderId = ctx.orderId,
                    orderState = OrderStatus.COMPENSATING.name,
                    correlationId = ctx.correlationId,
                    failureClass = failureClass,
                    detail = "Inventory confirmation returned $code for reservation ${ctx.reservationId}"
                        + (detail?.let { ": $it" } ?: ""),
                )
            } else {
                null
            }
        }

        if (orderService.getOrderById(ctx.orderId)?.status != OrderStatus.COMPENSATING) {
            throw IllegalStateException(
                "Order ${ctx.orderId}: could not enter COMPENSATING after Inventory refused with $code",
            )
        }

        // Commit-aware: a record that rolled back must not alert.
        record?.let { reconciliationService.countRequired(it) }

        ctx.compensationReason = code.name
        return Step.Advance(OrderStatus.COMPENSATING)
    }

    private suspend fun stageCompensate(ctx: PipelineContext): Step {
        // 1. Reverse the money first (doc:143). An unknown reversal throws, keeping COMPENSATING.
        meterRegistry.counter("orders.refund.attempt").increment()
        when (val reversal = paymentClient.refund(ctx.orderId, "refund-${ctx.orderId}")) {
            is ReversalOutcome.Failed if ctx.compensationReason == ABANDONED_COMPENSATION_REASON &&
                (reversal.isNoAuthorization() || reversal.isUnsettledAuthorization()) -> {
                // #140: an abandoned checkout never learned whether its payment was authorized, and
                // Payments holds no AUTHORIZED row to reverse. That does NOT mean no money was taken:
                // Payments calls the provider before writing its row, so a charge whose answer was lost
                // leaves none. Only an operator, from the provider, can say. Until one has settled it
                // with evidence, the order waits here under an incident that names the question.
                // Elsewhere compensation follows a definitive AUTHORIZED, and these answers stay a
                // reversal failure (below).
                if (!reconciliationService.isPaymentOutcomeSettled(ctx.orderId)) {
                    meterRegistry.counter("orders.refund", "result", "outcome_unknown", "code", reversal.code).increment()
                    // The incident first: if Inventory is down, the release below throws and the delivery
                    // is retried, but the money question is already visible to the operator.
                    reconciliationService.recordPaymentOutcomeUnknown(
                        orderId = ctx.orderId,
                        correlationId = ctx.correlationId,
                        failureClass = FailureClassifier.PAYMENT_OUTCOME_UNKNOWN,
                        detail = "Payments holds no settled authorization: ${reversal.code}" +
                            (reversal.detail?.let { " $it" } ?: "") +
                            ". Check the provider before settling.",
                    )
                    // The sale is lost whatever the money: the hold expired before the abandonment.
                    releaseHoldIfSafe(ctx)
                    return Step.Park(OrderStatus.COMPENSATING, "payment outcome unknown after abandonment")
                }
                meterRegistry.counter("orders.refund", "result", "settled_by_operator", "code", reversal.code).increment()
            }
            is ReversalOutcome.Failed -> {
                meterRegistry.counter("orders.refund", "result", "failed", "code", reversal.code).increment()
                reconciliationService.recordPaymentReversalFailure(
                    orderId = ctx.orderId,
                    orderState = OrderStatus.COMPENSATING.name,
                    correlationId = ctx.correlationId,
                    failureClass = FailureClassifier.PAYMENT_REVERSAL_FAILED,
                    detail = "Payments refused the reversal with ${reversal.code}"
                        + (reversal.detail?.let { ": $it" } ?: ""),
                )
                // doc:306 — do NOT mark the order FAILED: the money is unresolved. Acknowledge the
                // delivery, because a definitive refusal will not resolve itself on redelivery and
                // dead-lettering it every time only adds noise to the same alert.
                return Step.Park(OrderStatus.COMPENSATING, "definitive reversal failure ${reversal.code}")
            }
            is ReversalOutcome.Reversed ->
                meterRegistry.counter("orders.refund", "result", "refunded", "code", "none").increment()
        }

        // 2. Only now release any remaining hold (doc:143).
        releaseHoldIfSafe(ctx)

        // 3. FAILED only after the reversal succeeded — enforced by SQL, since markFailedAfterReversal
        //    accepts only COMPENSATING and no statement goes CONFIRMING_INVENTORY -> FAILED.
        val reason = ctx.compensationReason ?: FailureClassifier.UNKNOWN
        val publicCode = CustomerFailureCode.from(reason)
        val failed = txOperator.executeAndAwait {
            if (orderService.markFailedAfterReversal(ctx.orderId, publicCode) == 0L) {
                false
            } else {
                outboxEventRepository.save(failureNotification(ctx, publicCode))
                reconciliationService.closeAutoResolvable(ctx.orderId)
                true
            }
        }
        if (failed != true) {
            val current = orderService.getOrderById(ctx.orderId)?.status
            if (current == OrderStatus.FAILED) return Step.Advance(OrderStatus.FAILED)
            throw IllegalStateException(
                "Order ${ctx.orderId}: reversal succeeded but the terminal commit affected 0 rows (state=$current)",
            )
        }

        // Normalised through the enum, like the reversal path below: the reason is read back from the
        // row, and one written under a code since renamed must not add a series nobody registered.
        val label = if (reason == ABANDONED_COMPENSATION_REASON) {
            FailureClassifier.CHECKOUT_ABANDONED
        } else {
            InventoryRefusalCode.entries.find { it.name == reason }
                ?.let(FailureClassifier::forInventoryRefusal) ?: FailureClassifier.UNKNOWN
        }
        countFailed(ctx, label)
        return Step.Advance(OrderStatus.FAILED)
    }

    // --- shared endings ---------------------------------------------------------------------------

    /**
     * Terminal FAILED for a checkout that never authorized a payment, so no reversal is due. Optionally
     * releases the hold first.
     */
    private suspend fun failUnpaid(ctx: PipelineContext, reason: String, releaseHold: Boolean): Step {
        if (releaseHold) {
            ctx.reservationId?.let { releaseHold(ctx, it) }
        }

        // The internal label stays on the metric and the reconciliation record; only its public
        // projection reaches the order row and the customer notification.
        val publicCode = CustomerFailureCode.from(reason)
        val failed = txOperator.executeAndAwait {
            if (orderService.markFailedUnpaid(ctx.orderId, publicCode) == 0L) {
                false
            } else {
                outboxEventRepository.save(failureNotification(ctx, publicCode))
                reconciliationService.closeAutoResolvable(ctx.orderId)
                true
            }
        }
        if (failed != true) {
            logger.info("Order {} was cancelled during the pipeline; left CANCELLED", ctx.orderId)
            return Step.Advance(OrderStatus.CANCELLED)
        }

        countFailed(ctx, reason)
        return Step.Advance(OrderStatus.FAILED)
    }

    /**
     * Releases the hold during compensation, but only when the stored failure class says it is ours to
     * release.
     *
     * Reading [PipelineContext.compensationReason] — which is persisted — rather than an in-memory flag
     * is what makes this behave identically on a fresh pass and on a post-crash resume. Skipping
     * RESERVATION_ORDER_CONFLICT matters: that hold belongs to a different order, and releasing it
     * would corrupt a stranger's checkout.
     */
    private suspend fun releaseHoldIfSafe(ctx: PipelineContext) {
        val reservationId = ctx.reservationId
        if (reservationId == null) {
            meterRegistry.counter("orders.inventory.release", "result", "skipped").increment()
            return
        }
        val code = ctx.compensationReason?.let { runCatching { InventoryRefusalCode.valueOf(it) }.getOrNull() }
        if (code != null && !code.isReleasable) {
            logger.info("Not releasing reservation {} for order {}: {}", reservationId, ctx.orderId, code)
            meterRegistry.counter("orders.inventory.release", "result", "skipped").increment()
            return
        }
        releaseHold(ctx, reservationId)
    }

    /**
     * Releases a hold. An unknown release outcome propagates (doc:307): the reversal has already
     * succeeded and is idempotent, so the next delivery replays it and retries the release.
     */
    private suspend fun releaseHold(ctx: PipelineContext, reservationId: String) {
        logger.info("Releasing reservation {} for order {}", reservationId, ctx.orderId)
        inventoryClient.releaseSeats(reservationId, "rel-${ctx.orderId}")
        meterRegistry.counter("orders.inventory.release", "result", "released").increment()
    }

    /**
     * Cancels an order. Accepted only while no payment authorization has started, i.e. ACCEPTED,
     * RESERVING or HELD_AWAITING_PAYMENT (doc:147-149, ADR-016); from PAYMENT_PENDING onward this is a 409, which is what closes the
     * cancellation race before the money moves. Ownership is enforced by the controller; [actorId] is
     * who asked — the owner or `operations` staff — and is recorded on the audit line.
     */
    suspend fun cancel(orderId: UUID, actorId: String): Order {
        val correlationId = currentCorrelationId()
        val cancelled = txOperator.executeAndAwait {
            if (orderService.cancelIfUnpaid(orderId) == 0L) {
                false
            } else {
                reconciliationService.closeAutoResolvable(orderId)
                true
            }
        }
        if (cancelled != true) {
            val current = orderService.getOrderById(orderId)
                ?: throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")
            MoneyPathAudit.record(
                MoneyPathAudit.Action.CANCEL, MoneyPathAudit.Outcome.REJECTED, actorId, orderId, correlationId,
                reason = "ORDER_NOT_CANCELLABLE", ownerId = current.userId,
            )
            throw ApiException(
                HttpStatus.CONFLICT,
                "ORDER_NOT_CANCELLABLE",
                "Order in state ${current.status} cannot be cancelled (payment has started or it is terminal)",
            )
        }

        val order = orderService.getOrderById(orderId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")

        // Best-effort: release is idempotent, so overlapping with the pipeline's own release is safe.
        // A hold taken but not yet stored is released by the pipeline instead (see stageHold).
        order.reservationId?.let { rid ->
            try {
                inventoryClient.releaseSeats(rid, "rel-$orderId")
            } catch (e: Exception) {
                logger.error("Failed to release seats for cancelled order {} (reservation {})", orderId, rid, e)
            }
        }

        meterRegistry.counter("orders.cancel", "result", "cancelled").increment()
        MoneyPathAudit.record(
            MoneyPathAudit.Action.CANCEL, MoneyPathAudit.Outcome.CANCELLED, actorId, orderId, correlationId,
            ownerId = order.userId,
        )
        logger.info("Order {} cancelled", orderId)
        return order
    }

    /**
     * Closes customer-hold orders past their T1 deadline (ADR-016), at most one batch per call, and
     * returns how many this call closed.
     *
     * `CANCELLED`, not `FAILED`: the customer simply did not pay, so no failure notification is sent
     * and no failure code is recorded. The deadline is re-checked by the compare-and-set itself, so a
     * customer whose payment committed after the candidates were read keeps their order: the two
     * predicates are opposite, and the row lock decides which statement ran first.
     *
     * The release is best-effort, as for a customer cancellation: Inventory's own sweep frees the seat
     * at the same deadline, so a failed release only means the seat waits for that sweep.
     */
    suspend fun expireHolds(): Int {
        var closed = 0
        for (candidate in orderService.findExpiredHolds(HOLD_EXPIRY_BATCH)) {
            val cancelled = txOperator.executeAndAwait {
                if (orderService.cancelExpiredHold(candidate.id) == 0L) {
                    false
                } else {
                    reconciliationService.closeAutoResolvable(candidate.id)
                    true
                }
            }
            if (cancelled != true) {
                meterRegistry.counter("orders.holds.expiry", "result", "lost_race").increment()
                continue
            }
            closed++
            meterRegistry.counter("orders.holds.expiry", "result", "cancelled").increment()
            MoneyPathAudit.record(
                MoneyPathAudit.Action.HOLD_EXPIRY, MoneyPathAudit.Outcome.CANCELLED, SYSTEM_ACTOR,
                candidate.id, correlationId = null, ownerId = candidate.userId,
            )
            candidate.reservationId?.let { rid ->
                try {
                    inventoryClient.releaseSeats(rid, "rel-${candidate.id}")
                    meterRegistry.counter("orders.inventory.release", "result", "released").increment()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    logger.warn(
                        "Release failed for expired hold of order {} (reservation {}); the Inventory sweep frees it",
                        candidate.id, rid, e,
                    )
                }
            }
        }
        return closed
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * The correlation id the inbound WebFilter put in the Reactor context, or a fresh one when the call
     * is not driven through the filter (e.g. unit tests).
     */
    private suspend fun currentCorrelationId(): String =
        currentCoroutineContext()[ReactorContext]?.context
            ?.getOrEmpty<String>(CorrelationId.CONTEXT_KEY)?.orElse(null)
            ?: CorrelationId.generate()

    /**
     * The customer-facing failure notification.
     *
     * The message is the [CustomerFailureCode]'s own text and never the internal failure class. It
     * used to interpolate the latter, which put strings like `reservation_seat_conflict` — and, for
     * a dependency fault, a wire code — in front of a customer.
     *
     * The code travels beside the message so a client can branch on it without parsing the sentence
     * or, worse, inferring the reason from an HTTP status: Inventory answers 409 for five different
     * conditions and none of them means the seats were taken, while the refusal that does mean it is
     * not a 409 at all. The message stays the authority on anything to do with money.
     */
    private suspend fun failureNotification(ctx: PipelineContext, code: CustomerFailureCode): OutboxEvent = outbox(
        "notification-requested", ctx.orderId,
        NotificationRequestedEvent(
            correlationId = ctx.correlationId,
            orderId = ctx.orderId,
            principalId = ctx.order.userId,
            payload = NotificationRequestedPayload(
                recipientEmail = recipientEmail(ctx.order),
                message = code.customerMessage,
                type = NotificationType.ORDER_FAILED,
                failureCode = code.name,
                routeId = ctx.order.routeId,
                travelDate = ctx.order.travelDate,
            ),
        ),
    )

    private suspend fun outbox(type: String, orderId: UUID, event: Any): OutboxEvent =
        orderOutbox.row(type, orderId, event)

    // Delivery address for the notification, captured at checkout from the JWT `email` claim. If the
    // token had no email, fall back to a placeholder and warn — the checkout must not fail for this.
    private fun recipientEmail(order: Order): String {
        val email = order.recipientEmail
        if (email.isNullOrBlank()) {
            logger.warn("Order {} has no recipient email (missing JWT email claim); notification will use a placeholder", order.id)
            return "unknown@eurotransit.invalid"
        }
        return email
    }

    private fun countHold(result: String, code: String) =
        meterRegistry.counter("orders.inventory.hold", "result", result, "code", code).increment()

    private fun countConfirm(result: String, code: String) =
        meterRegistry.counter("orders.inventory.confirm", "result", result, "code", code).increment()

    /** Both FAILED endings come through here, so it is also where the failure is audited. */
    private fun countFailed(ctx: PipelineContext, reason: String) {
        meterRegistry.counter("orders.checkout.completed", "result", "failed").increment()
        meterRegistry.counter("orders.checkout.failed", "reason", reason).increment()
        recordEndToEnd(ctx, "failed")
        MoneyPathAudit.record(
            MoneyPathAudit.Action.CHECKOUT_PIPELINE, MoneyPathAudit.Outcome.FAILED,
            ctx.order.userId, ctx.orderId, ctx.correlationId, reason = reason,
        )
    }

    /**
     * Time from accepting the order to reaching a terminal state. Unlike the per-delivery timer this
     * survives redeliveries, which makes it the only correct checkout SLO measurement.
     */
    private fun recordEndToEnd(ctx: PipelineContext, outcome: String) {
        // From the accepted payment for a customer-hold order (ADR-016): before it the customer was
        // reading the checkout page, which is not checkout latency. A direct-flow order's payment
        // arrived with the order, so its start is still the creation time.
        val elapsed = ctx.order.paymentSubmittedAt?.let { Duration.between(it, OffsetDateTime.now()) }
            ?: Duration.between(ctx.order.createdAt, LocalDateTime.now())
        CheckoutMetrics.recordEndToEnd(meterRegistry, elapsed, outcome)
    }
}
