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
import it.polito.cpo.client.ResolvedOffer
import it.polito.cpo.client.ReversalOutcome
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.controller.dtos.CheckoutRequest
import it.polito.cpo.controller.dtos.CheckoutResponse
import it.polito.cpo.contracts.events.NotificationRequestedEvent
import it.polito.cpo.contracts.events.NotificationRequestedPayload
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
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
) {
    private val logger = LoggerFactory.getLogger(CheckoutOrchestrator::class.java)

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
    }

    /** One delivery's working state. [reservationId] is seeded from the row, so a resume needs no lookup. */
    private class PipelineContext(val order: Order, val correlationId: String) {
        val orderId: UUID get() = order.id
        var reservationId: String? = order.reservationId
        var compensationReason: String? = order.compensationReason
    }

    /**
     * The outcome of one stage. Anything not representable here is an exception, which is what keeps
     * unknown outcomes out of the control flow.
     */
    private sealed interface Step {
        /** A durable transition committed; keep dispatching on [next]. */
        data class Advance(val next: OrderStatus) : Step

        /** Durable state intentionally left as is. Stop and acknowledge the delivery. */
        data class Park(val state: OrderStatus, val reason: String) : Step
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
        val correlationId = currentCoroutineContext()[ReactorContext]?.context
            ?.getOrEmpty<String>(CorrelationId.CONTEXT_KEY)?.orElse(null)
            ?: CorrelationId.generate()

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
            return objectMapper.readValue(existing.responseBody, CheckoutResponse::class.java)
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

        return response
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
            append(request.paymentMethodToken)
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
        if (order.status != OrderStatus.ACCEPTED) {
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
                        logger.warn("Order {} parked in {}: {}", ctx.orderId, step.state, step.reason)
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
        OrderStatus.PAYMENT_PENDING -> stageAuthorize(ctx)
        OrderStatus.CONFIRMING_INVENTORY -> stageConfirmInventory(ctx)
        OrderStatus.COMPENSATING -> stageCompensate(ctx)
        OrderStatus.CONFIRMED, OrderStatus.FAILED, OrderStatus.CANCELLED ->
            error("unreachable: terminal states are filtered by the loop")
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
                if (orderService.markPaymentPending(ctx.orderId, outcome.reservationId, outcome.expiresAt) == 0L) {
                    // doc:298 — a cancellation won the race while the hold was in flight. The order is
                    // CANCELLED with no reservation id stored, so the pipeline is the only place that
                    // can release this hold.
                    logger.info("Order {} cancelled while the hold was in flight; releasing", ctx.orderId)
                    releaseHold(ctx, outcome.reservationId)
                    return Step.Advance(OrderStatus.CANCELLED)
                }
                Step.Advance(OrderStatus.PAYMENT_PENDING)
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
                                recipientEmail = recipientEmail(ctx.order),
                                message = "Your order ${ctx.orderId} has been successfully confirmed!",
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
            ReversalOutcome.Reversed ->
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

        countFailed(ctx, reason.lowercase())
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
     * Cancels an order. Accepted only while no payment authorization has started, i.e. ACCEPTED or
     * RESERVING (doc:147-149); from PAYMENT_PENDING onward this is a 409, which is what closes the
     * cancellation race before the money moves. Ownership is enforced by the controller.
     */
    suspend fun cancel(orderId: UUID): Order {
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
        logger.info("Order {} cancelled", orderId)
        return order
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * The customer-facing failure notification.
     *
     * The message is the [CustomerFailureCode]'s own text and never the internal failure class. It
     * used to interpolate the latter, which put strings like `reservation_seat_conflict` — and, for
     * a dependency fault, a wire code — in front of a customer.
     */
    private fun failureNotification(ctx: PipelineContext, code: CustomerFailureCode): OutboxEvent = outbox(
        "notification-requested", ctx.orderId,
        NotificationRequestedEvent(
            correlationId = ctx.correlationId,
            orderId = ctx.orderId,
            principalId = ctx.order.userId,
            payload = NotificationRequestedPayload(
                recipientEmail = recipientEmail(ctx.order),
                message = code.customerMessage,
            ),
        ),
    )

    private fun outbox(type: String, orderId: UUID, event: Any): OutboxEvent =
        orderOutboxRow(objectMapper, type, orderId, event)

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

    private fun countFailed(ctx: PipelineContext, reason: String) {
        meterRegistry.counter("orders.checkout.completed", "result", "failed").increment()
        meterRegistry.counter("orders.checkout.failed", "reason", reason).increment()
        recordEndToEnd(ctx, "failed")
    }

    /**
     * Time from accepting the order to reaching a terminal state. Unlike the per-delivery timer this
     * survives redeliveries, which makes it the only correct checkout SLO measurement.
     */
    private fun recordEndToEnd(ctx: PipelineContext, outcome: String) {
        val elapsed = Duration.between(ctx.order.createdAt, LocalDateTime.now())
        if (!elapsed.isNegative) {
            meterRegistry.timer("orders.checkout.e2e.duration", "outcome", outcome).record(elapsed)
        }
    }
}
