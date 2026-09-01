package it.polito.cpo.service

import it.polito.cpo.contracts.inventory.SeatAvailabilityResponse
import it.polito.cpo.contracts.inventory.SeatDto
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.SeatRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class SeatService(
    private val seatRepository: SeatRepository
) {
    /**
     * Retrieves all seats for a given route and travel date, mapping them to privacy-safe DTOs.
     */
    suspend fun getAvailability(routeId: String, travelDate: LocalDate): SeatAvailabilityResponse {
        val seats = seatRepository.findByRouteIdAndTravelDate(routeId, travelDate).map { seat ->
            SeatDto(
                seatId = seat.seatId,
                isAvailable = seat.status == "AVAILABLE"
            )
        }.toList()
        
        if (seats.isEmpty()) {
            throw ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "No seats found for route $routeId on $travelDate")
        }
        
        return SeatAvailabilityResponse(
            routeId = routeId,
            travelDate = travelDate,
            seats = seats
        )
    }
}
