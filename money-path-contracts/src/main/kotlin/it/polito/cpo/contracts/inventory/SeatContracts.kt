package it.polito.cpo.contracts.inventory

import java.time.LocalDate

data class SeatDto(
    val seatId: String,
    val isAvailable: Boolean
)

data class SeatAvailabilityResponse(
    val routeId: String,
    val travelDate: LocalDate,
    val seats: List<SeatDto>
)
