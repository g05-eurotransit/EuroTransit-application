package it.polito.cpo.controller

import it.polito.cpo.contracts.inventory.SeatAvailabilityResponse
import it.polito.cpo.service.SeatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/inventory")
class SeatController(
    private val seatService: SeatService
) {

    /**
     * Retrieves all seats and their availability for a specific route and travel date.
     * Accessible only via internal service-to-service calls (requires ROLE_service).
     */
    @GetMapping("/availability")
    suspend fun getAvailability(
        @RequestParam routeId: String,
        @RequestParam travelDate: LocalDate
    ): ResponseEntity<SeatAvailabilityResponse> {
        val response = seatService.getAvailability(routeId, travelDate)
        return ResponseEntity.ok(response)
    }
}
