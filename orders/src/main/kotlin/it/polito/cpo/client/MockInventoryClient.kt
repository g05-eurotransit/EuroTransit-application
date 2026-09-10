package it.polito.cpo.client

import it.polito.cpo.contracts.inventory.ReservationRequest
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Local `dev` stand-in for Inventory, used when no real Inventory is running.
 *
 * The reservation id is derived from the order id rather than random: the hold is idempotent on
 * `inv-{orderId}`, so a replay must resolve the same reservation. A random id per call would make the
 * `markPaymentPending` compare-and-set — which pins the order to one reservation id — report 0 rows on
 * any redelivery, and the pipeline would look broken only in `dev`.
 */
@Component
@Profile("dev")
class MockInventoryClient : InventoryClient {

    override suspend fun reserveSeats(
        request: ReservationRequest,
        idempotencyKey: String,
        userId: String,
    ): HoldOutcome = HoldOutcome.Held(
        reservationId = reservationIdFor(request.orderId),
        expiresAt = OffsetDateTime.now().plusMinutes(10),
    )

    override suspend fun confirmReservation(
        reservationId: String,
        orderId: UUID,
    ): ConfirmReservationOutcome = ConfirmReservationOutcome.Confirmed(reservationId)

    override suspend fun releaseSeats(reservationId: String, idempotencyKey: String) {
        // Mock release - no operational work needed
    }

    private fun reservationIdFor(orderId: UUID) = "mock-res-$orderId"
}
