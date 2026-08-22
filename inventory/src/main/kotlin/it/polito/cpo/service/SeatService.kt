package it.polito.cpo.service

import it.polito.cpo.contracts.inventory.RouteSeatsResponse
import it.polito.cpo.contracts.inventory.SeatDto
import it.polito.cpo.contracts.inventory.SeatStatus
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.SeatRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class SeatService(
    private val seatRepository: SeatRepository
) {
    /**
     * Retrieves all seats for a given route and maps them to DTOs.
     */
    suspend fun getSeatsForRoute(routeId: String): RouteSeatsResponse {
        val seats = seatRepository.findByRouteId(routeId).map { seat ->
            SeatDto(
                seatId = seat.seatId,
                status = SeatStatus.valueOf(seat.status)
            )
        }.toList()
        
        if (seats.isEmpty()) {
            throw ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "No seats found for route $routeId")
        }
        
        return RouteSeatsResponse(
            routeId = routeId,
            seats = seats
        )
    }
}
