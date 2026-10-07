package it.polito.cpo.service

import it.polito.cpo.client.InventoryClient
import it.polito.cpo.client.PaymentClient
import it.polito.cpo.client.RefundReservationOutcome
import it.polito.cpo.client.ReversalOutcome
import it.polito.cpo.contracts.events.RefundRequestedEvent
import it.polito.cpo.controller.dtos.OrderView
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.observability.ApiException
import it.polito.cpo.observability.CorrelationId
import it.polito.cpo.repository.OutboxEventRepository
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.UUID

/**
 * The post-sale refund (ADR-015): a distinct durable process beside the checkout pipeline, with its
 * own states (`CONFIRMED -> REFUNDING -> REFUNDED`), its own trigger (`refund-requested`, never
 * `order-placed`), and its own idempotency namespace on the Payments call (`pay-refund-{orderId}`,
 * never `refund-{orderId}` — that key is already consumed by checkout compensation, and Payments
 * would replay its stored result: a `200` while no money moves).
 *
 * [refund] is the synchronous entry: it decides *whether* a refund is allowed and, if so, commits
 * `REFUNDING` plus the `refund-requested` outbox row in one transaction — mirroring how
 * [CheckoutOrchestrator.checkout] commits `ACCEPTED` plus `order-placed` — then returns. It does
 * **not** reverse anything itself: a slow or failing Payments/Inventory call must not hold this HTTP
 * request open, and ADR-015 is explicit that the reversal needs its own durable, resumable trigger.
 *
 * [processRefundRequested] is that trigger's consumer, driven by `RefundRequestedEventListener`. It
 * reverses the money first and returns the seat second (ADR-015): if the seat return then fails
 * definitively, the customer already has their money and a `SEAT_RETURN_FAILURE` record stays open
 * with the order in `REFUNDING` — there is deliberately no `REFUND_FAILED` terminal state, exactly as
 * `COMPENSATING` has none on the checkout side.
 *
 * Both remote calls are safe to repeat unconditionally on every delivery, so a redelivery after a
 * crash needs no bookkeeping of "which step already succeeded": `pay-refund-{orderId}` is a
 * deterministic idempotency key at Payments, and Inventory's refund endpoint is idempotent by
 * reservation state (an already-REFUNDED reservation replays without erroring). This is the same
 * "repeat the idempotent command" shape `stageHold`/`stageCompensate` already rely on.
 */
@Service
class RefundOrchestrator(
    private val orderService: OrderService,
    private val inventoryClient: InventoryClient,
    private val paymentClient: PaymentClient,
    private val outboxEventRepository: OutboxEventRepository,
    private val reconciliationService: ReconciliationService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val txOperator: TransactionalOperator,
    private val orderOutbox: OrderOutbox,
) {
    private val logger = LoggerFactory.getLogger(RefundOrchestrator::class.java)

    private companion object {
        const val OPERATION_REFUND = "refund"
    }

    /**
     * Requests a refund for [orderId]. Ownership is enforced by the caller (`OrderController`, same
     * 404-for-non-owner rule as `cancel`); this method assumes [userId]/[isStaff] already cleared that
     * gate and re-checks it anyway so a caller cannot bypass it by calling the service directly.
     *
     * Refuses, each with its own `409` code: the order is not `CONFIRMED`
     * (`ORDER_NOT_CONFIRMED`); it carries no `offerId` and therefore has no refund terms
     * (`ORDER_HAS_NO_OFFER`); the offer it was sold under is not refundable (`OFFER_NOT_REFUNDABLE`);
     * or the request arrives after `departure_at` minus the cutoff (`REFUND_CUTOFF_PASSED`).
     *
     * **The replay rule is two opposite behaviours on one time condition.** An idempotency key
     * already accepted before the cutoff must return its original result even when the retry arrives
     * after it; a *new* request after the cutoff must be refused. [replayed] runs before any of the
     * four checks above — including the cutoff — which is what makes the two behaviours fall out of
     * the ordering rather than needing a special case.
     */
    suspend fun refund(orderId: UUID, idempotencyKey: String, userId: String, isStaff: Boolean): OrderView {
        val correlationId = correlationId()
        val order = orderService.getOrderById(orderId)
        if (order == null || (order.userId != userId && !isStaff)) {
            // Same rule as getOrder/cancel: a non-owner gets 404, never 403, so the API never reveals
            // that another customer's order exists.
            throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")
        }

        val fingerprint = fingerprint(orderId)
        replayed(idempotencyKey, fingerprint)?.let { return it }

        requireEligible(order)

        val committed = txOperator.executeAndAwait {
            if (orderService.markRefunding(orderId) == 0L) {
                null
            } else {
                // Mutated in memory rather than re-read: the UPDATE just committed the only field
                // this response shows that changed (status), and requireEligible already proved the
                // rest of the row is what the response needs.
                order.status = OrderStatus.REFUNDING
                val view = toOrderView(order)
                orderService.saveIdempotentRequest(
                    idempotencyKey,
                    objectMapper.writeValueAsString(view),
                    userId,
                    OPERATION_REFUND,
                    fingerprint,
                )
                outboxEventRepository.save(
                    orderOutbox.row(
                        "refund-requested", orderId,
                        RefundRequestedEvent(
                            correlationId = correlationId,
                            orderId = orderId,
                            principalId = order.userId,
                        ),
                    ),
                )
                view
            }
        }

        if (committed != null) {
            meterRegistry.counter("orders.postsale_refund.requested").increment()
            logger.info("Refund accepted for order {} (correlationId={})", orderId, correlationId)
            return committed
        }

        // 0 rows from markRefunding: either a concurrent request already moved the order past
        // CONFIRMED, or the cutoff has passed since requireEligible read the row a moment ago.
        // offerId/refundable cannot change once V13/V14 write them at checkout, so re-running the
        // same static checks tells the two apart — if they still pass, only the cutoff explains it.
        val current = orderService.getOrderById(orderId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found")
        requireEligible(current)
        reject(orderId, "REFUND_CUTOFF_PASSED", "The refund cutoff for order $orderId has passed")
    }

    /**
     * The three static preconditions markRefunding's SQL also enforces, checked here first so the
     * common case gets a precise code without needing the 0-rows re-read, and re-run after a 0-rows
     * result to isolate the one condition a static check cannot: the cutoff.
     *
     * Does **not** check `departureAt != null` even though `markRefunding`'s SQL requires
     * `departure_at IS NOT NULL` too: `CheckoutOrchestrator` writes `offerId`, `refundable` and
     * `departureAt` together from the same resolved offer (V13/V14), so `refundable == true` implies
     * `departureAt != null` for every row this code can reach today. If that coupling is ever broken
     * — a future migration, a decoupled write path — a row with `refundable = true` and a null
     * `departureAt` would pass every check here, fail `markRefunding`'s SQL, and be reported as
     * `REFUND_CUTOFF_PASSED`, which would be the wrong reason. Worth an explicit check only if that
     * coupling stops holding.
     */
    private fun requireEligible(order: Order) {
        if (order.status != OrderStatus.CONFIRMED) {
            reject(order.id, "ORDER_NOT_CONFIRMED", "Order in state ${order.status} is not eligible for a refund")
        }
        if (order.offerId == null) {
            reject(order.id, "ORDER_HAS_NO_OFFER", "Order was not sold against an offer and has no refund terms")
        }
        if (order.refundable != true) {
            reject(order.id, "OFFER_NOT_REFUNDABLE", "The offer this order was bought against is not refundable")
        }
    }

    /** A `409` refusal, counted under its own reason tag. */
    private fun reject(orderId: UUID, code: String, message: String): Nothing {
        meterRegistry.counter("orders.postsale_refund.rejected", "reason", code.lowercase()).increment()
        logger.info("Refund refused for order {}: {}", orderId, code)
        throw ApiException(HttpStatus.CONFLICT, code, message)
    }

    /**
     * Entry point for the `refund-requested` consumer. Reads the durable state fresh, so a
     * redelivery resumes rather than restarting — the same shape as
     * [CheckoutOrchestrator.processOrderPlaced], but on its own predicate rather than
     * `OrderStatus.isTerminal`: that predicate answers `true` for both REFUNDING and REFUNDED (so
     * the CHECKOUT consumer leaves either alone), which is the opposite of what this consumer needs.
     * REFUNDING is exactly the state this one resumes.
     */
    suspend fun processRefundRequested(event: RefundRequestedEvent) {
        val order = orderService.getOrderById(event.orderId)
        if (order == null) {
            logger.warn("Received refund-requested for unknown order: {}", event.orderId)
            return
        }

        when (order.status) {
            OrderStatus.REFUNDING -> runRefundPipeline(order, event.correlationId)
            OrderStatus.REFUNDED -> {
                meterRegistry.counter("orders.postsale_refund.duplicate", "state", order.status.name).increment()
                logger.info("Skipping refund-requested for order {}; already REFUNDED", event.orderId)
            }
            else -> {
                // REFUNDING is entered together with this very outbox row, in the same transaction
                // (refund() above), so a well-formed event always finds the order in one of the two
                // states handled above. Anything else means the model's own invariant does not hold —
                // logged rather than thrown, because throwing would only dead-letter a message that
                // will never parse differently on redelivery.
                logger.error(
                    "Received refund-requested for order {} in unexpected state {}",
                    event.orderId, order.status,
                )
            }
        }
    }

    private suspend fun runRefundPipeline(order: Order, correlationId: String) {
        val orderId = order.id

        // 1. Reverse the money first (ADR-015). Always attempted, even on a resume: `pay-refund-
        // {orderId}` is a deterministic idempotency key at Payments, so a redelivery replays the
        // stored result there rather than moving money twice.
        meterRegistry.counter("orders.postsale_refund.reversal.attempt").increment()
        val reversal = paymentClient.refund(orderId, "pay-refund-$orderId")
        val providerReference = when (reversal) {
            is ReversalOutcome.Failed -> {
                meterRegistry.counter(
                    "orders.postsale_refund.reversal", "result", "failed", "code", reversal.code,
                ).increment()
                reconciliationService.recordPaymentReversalFailure(
                    orderId = orderId,
                    orderState = OrderStatus.REFUNDING.name,
                    correlationId = correlationId,
                    failureClass = FailureClassifier.PAYMENT_REVERSAL_FAILED,
                    detail = "Payments refused the post-sale refund reversal with ${reversal.code}"
                        + (reversal.detail?.let { ": $it" } ?: ""),
                )
                // Unresolved money must not be reported as finished (ADR-015): the order stays
                // REFUNDING. Acknowledge the delivery rather than throw — a definitive refusal will
                // not resolve itself on redelivery, and dead-lettering it every time only adds noise
                // to the same alert (the same reasoning stageCompensate uses for this same outcome).
                logger.warn("Order {} refund reversal definitively failed: {}", orderId, reversal.code)
                return
            }
            is ReversalOutcome.Reversed -> {
                meterRegistry.counter(
                    "orders.postsale_refund.reversal", "result", "reversed", "code", "none",
                ).increment()
                reversal.providerReference
            }
        }

        // 2. Only now return the seat (ADR-015).
        val reservationId = order.reservationId
            ?: throw IllegalStateException("Order $orderId: REFUNDING without a stored reservation id")

        meterRegistry.counter("orders.postsale_refund.seat_return.attempt").increment()
        when (val seatReturn = inventoryClient.refundReservation(reservationId, orderId)) {
            is RefundReservationOutcome.Refused -> {
                meterRegistry.counter(
                    "orders.postsale_refund.seat_return", "result", "refused", "code", seatReturn.code.name,
                ).increment()
                reconciliationService.recordSeatReturnFailure(
                    orderId = orderId,
                    orderState = OrderStatus.REFUNDING.name,
                    correlationId = correlationId,
                    failureClass = FailureClassifier.forInventoryRefusal(seatReturn.code),
                    detail = "Inventory refused the seat return with ${seatReturn.code} " +
                        "for reservation $reservationId" + (seatReturn.detail?.let { ": $it" } ?: ""),
                )
                // The customer already has their money (step 1 above); one resaleable seat is
                // withheld until an operator looks. The order stays REFUNDING, never a terminal
                // failure (ADR-015).
                logger.warn(
                    "Order {} seat return definitively failed for reservation {}: {}",
                    orderId, reservationId, seatReturn.code,
                )
                return
            }
            is RefundReservationOutcome.Refunded ->
                meterRegistry.counter(
                    "orders.postsale_refund.seat_return", "result", "refunded", "code", "none",
                ).increment()
        }

        // 3. Only now REFUNDED, exactly as checkout only reaches CONFIRMED after Inventory confirms
        // the sale: both remote effects must be committed before the local terminal-for-money state
        // is.
        val committed = txOperator.executeAndAwait {
            if (orderService.markRefunded(orderId, providerReference) == 0L) {
                false
            } else {
                // Closes a PAYMENT_REVERSAL_FAILURE or SEAT_RETURN_FAILURE left by an earlier delivery
                // of this same order that has since converged (a redrive, or the redelivery itself).
                reconciliationService.closeAutoResolvable(orderId)
                true
            }
        }

        if (committed == true) {
            meterRegistry.counter("orders.postsale_refund.completed", "result", "refunded").increment()
            logger.info("Order {} refunded", orderId)
            return
        }

        // markRefunded has no self-transition, so 0 rows means either a concurrent delivery already
        // committed this order (idempotent: REFUNDED exactly once) or the row is in an unexpected
        // state, which is unknown and must be redriven rather than assumed.
        val current = orderService.getOrderById(orderId)?.status
        if (current == OrderStatus.REFUNDED) {
            meterRegistry.counter("orders.postsale_refund.duplicate", "state", OrderStatus.REFUNDED.name).increment()
            return
        }
        throw IllegalStateException(
            "Order $orderId: refund reversal and seat return both succeeded but the local commit " +
                "affected 0 rows (state=$current)",
        )
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Replays a stored result for a repeated key, or rejects the key if it was reused with a
     * different payload — the same contract as the public checkout endpoint (api-design.md, shared
     * headers) and `ReconciliationOperationsService`.
     */
    private suspend fun replayed(idempotencyKey: String, fingerprint: String): OrderView? {
        val existing = orderService.findIdempotentRequest(idempotencyKey) ?: return null
        if (existing.requestFingerprint != null && existing.requestFingerprint != fingerprint) {
            meterRegistry.counter("orders.postsale_refund.idempotency_conflict").increment()
            throw ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "Idempotency key was reused with a different request",
            )
        }
        meterRegistry.counter("orders.postsale_refund.idempotent_replay").increment()
        return objectMapper.readValue(existing.responseBody, OrderView::class.java)
    }

    private suspend fun correlationId(): String =
        currentCoroutineContext()[ReactorContext]?.context
            ?.getOrEmpty<String>(CorrelationId.CONTEXT_KEY)?.orElse(null)
            ?: CorrelationId.generate()

    // The request carries no body, so the order id is the whole logical payload: reusing a key for
    // two different orders is the only way to reuse it with a "different request".
    private fun fingerprint(orderId: UUID): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$OPERATION_REFUND|$orderId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Mirrors `OrderController.toView`. Duplicated rather than shared: that method is private to a
     * controller, and factoring a shared mapper out of it is a larger change than this step's scope —
     * left as a note for whoever touches either one next, since the two must stay in sync by hand.
     */
    private fun toOrderView(order: Order): OrderView {
        val failure = CustomerFailureCode.parse(order.failureCode)
        return OrderView(
            orderId = order.id,
            userId = order.userId,
            status = order.status,
            routeId = order.routeId,
            seats = order.seats.split(","),
            travelDate = order.travelDate,
            totalAmount = order.totalAmount,
            currency = order.currency,
            createdAt = order.createdAt,
            reservationExpiresAt = order.reservationExpiresAt,
            failureCode = failure?.name,
            failureMessage = failure?.customerMessage,
            offerId = order.offerId,
            refundable = order.refundable,
            refundCutoffMinutesBeforeDeparture = order.refundCutoffMinutesBeforeDeparture,
            departureAt = order.departureAt,
        )
    }
}
