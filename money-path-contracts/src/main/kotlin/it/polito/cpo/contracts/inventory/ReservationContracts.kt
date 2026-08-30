package it.polito.cpo.contracts.inventory

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// orders -> inventory : POST /api/inventory/reservations (Idempotency-Key header)
data class ReservationRequest(
    val orderId: UUID,
    val seats: List<String>,
    val routeId: String,
    val travelDate: LocalDate
)

enum class ReservationStatus { HELD, FAILED, CONFIRMED, EXPIRED, CANCELLED }

data class ReservationConfirmationRequest(
    val orderId: UUID
)

data class ReservationResponse(
    val reservationId: String,
    val status: ReservationStatus,
    /**
     * The hold deadline as an INSTANT, not a wall-clock reading.
     *
     * It must carry an offset. Inventory derives it from the database clock — the only authority for
     * expiry (reservation-confirmation.md, invariant 4) — and it is surfaced to the browser as a
     * countdown. A `LocalDateTime` here would force every consumer to guess a zone, and the browser
     * guesses its own, so the same ten-minute hold would read as nine hours or as already expired
     * depending on where the customer is.
     */
    val expiresAt: OffsetDateTime
)
