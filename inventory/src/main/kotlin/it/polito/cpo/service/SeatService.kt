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
        val currentDate = seatRepository.getCurrentDate()
        if (travelDate.isBefore(currentDate)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "TRAVEL_DATE_IN_THE_PAST", "Cannot query availability for past dates")
        }

        val seats = seatRepository.findByRouteIdAndTravelDate(routeId, travelDate).map { seat ->
            SeatDto(
                seatId = seat.seatId,
                isAvailable = seat.status == "AVAILABLE"
            )
        }.toList()
        
        if (seats.isEmpty()) {
            if (seatRepository.existsByRouteId(routeId)) {
                throw ApiException(HttpStatus.NOT_FOUND, "NO_SEATS_FOR_DATE", "No seats provisioned for route $routeId on $travelDate")
            } else {
                throw ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Route $routeId does not exist")
            }
        }
        
        return SeatAvailabilityResponse(
            routeId = routeId,
            travelDate = travelDate,
            seats = seats
        )
    }
}
