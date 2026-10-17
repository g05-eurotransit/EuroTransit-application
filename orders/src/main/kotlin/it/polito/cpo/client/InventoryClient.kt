package it.polito.cpo.client

import it.polito.cpo.contracts.inventory.ReservationRequest
import java.util.UUID

/**
 * Orders' view of the Inventory money-path commands.
 *
 * Every method returns a sealed outcome covering only DEFINITIVE results. Unknown outcomes — timeout,
 * connection failure, open circuit, 5xx, or any 4xx whose code Orders does not recognise — are thrown,
 * so the orchestrator cannot pattern-match them into a business decision and the Kafka offset is not
 * committed. See docs/design/reservation-confirmation.md:92 (invariant 8) and :303.
 */
interface InventoryClient {

    /**
     * Creates or replays the seat hold. [userId] is the end customer, propagated as `X-User-Id` so
     * Inventory records the real principal (the service token identifies the caller as a service).
     */
    suspend fun reserveSeats(request: ReservationRequest, idempotencyKey: String, userId: String): HoldOutcome

    /**
     * Transitions the reservation to CONFIRMED and its seats to SOLD, atomically inside Inventory.
     *
     * No `Idempotency-Key`: the resource identity plus the stored [orderId] provide natural
     * idempotency, so a replay after a lost response returns the same success (doc:169-170).
     */
    suspend fun confirmReservation(reservationId: String, orderId: UUID): ConfirmReservationOutcome

    /**
     * Opens the one-time T2 payment window of a customer-hold reservation (ADR-016). Called before
     * Payments, never after.
     *
     * No `Idempotency-Key`: idempotent by reservation state, like [confirmReservation]. A replay after
     * the window opened returns the same deadline and never extends it again.
     */
    suspend fun startPaymentWindow(reservationId: String, orderId: UUID): PaymentWindowOutcome

    /**
     * Releases a hold. Inventory's release only ever touches HELD rows, so releasing an already
     * terminal reservation is an idempotent no-op and has no business refusal to report.
     */
    suspend fun releaseSeats(reservationId: String, idempotencyKey: String)

    /**
     * Returns a sold seat to the pool after a post-sale refund's payment reversal has already
     * succeeded (ADR-015). On the same money-path resilience chain as the three calls above, unlike
     * the read-only clients in [InventoryReadClient] — this is the money path, not a browsing read.
     *
     * No `Idempotency-Key`: the endpoint is idempotent by reservation state, not by key (see
     * [RefundReservationOutcome.Refunded]).
     */
    suspend fun refundReservation(reservationId: String, orderId: UUID): RefundReservationOutcome
}
