package it.polito.cpo.contracts.inventory

import java.time.LocalDate
import java.time.LocalDateTime
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
    val expiresAt: LocalDateTime
)
