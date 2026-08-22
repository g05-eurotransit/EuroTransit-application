package it.polito.cpo.contracts.inventory

enum class SeatStatus { 
    AVAILABLE, 
    HELD, 
    SOLD 
}

data class SeatDto(
    val seatId: String,
    val status: SeatStatus
)

data class RouteSeatsResponse(
    val routeId: String,
    val seats: List<SeatDto>
)
