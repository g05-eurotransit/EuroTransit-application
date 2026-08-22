package it.polito.cpo.controller

import it.polito.cpo.contracts.inventory.RouteSeatsResponse
import it.polito.cpo.service.SeatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/inventory/routes")
class SeatController(
    private val seatService: SeatService
) {

    /**
     * Retrieves all seats and their availability for a specific route.
     */
    @GetMapping("/{routeId}/seats")
    suspend fun getRouteSeats(
        @PathVariable routeId: String
    ): ResponseEntity<RouteSeatsResponse> {
        val response = seatService.getSeatsForRoute(routeId)
        return ResponseEntity.ok(response)
    }
}
