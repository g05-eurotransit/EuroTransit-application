package it.polito.cpo.controller

import it.polito.cpo.contracts.inventory.ReservationConfirmationRequest
import it.polito.cpo.contracts.inventory.ReservationRefundRequest
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationResponse
import it.polito.cpo.contracts.inventory.ReservationStatus
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

import it.polito.cpo.service.ReservationService


@RestController
@RequestMapping("/api/inventory/reservations")
class ReservationController(
    private val reservationService: ReservationService
) {

    /**
     * Handles seat reservation requests with idempotency validation.
     */
    @PostMapping
    suspend fun createReservation(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-Correlation-Id") correlationId: String,
        @RequestHeader("X-User-Id") userId: String,
        @RequestBody request: ReservationRequest
    ): ResponseEntity<ReservationResponse> {
        val response = reservationService.reserveSeats(idempotencyKey, userId, correlationId, request)
        val httpStatus = if (response.status == ReservationStatus.FAILED) HttpStatus.CONFLICT else HttpStatus.CREATED
        return ResponseEntity.status(httpStatus).body(response)
    }

    /**
     * Handles requests to cancel and release an existing reservation.
     */
    @DeleteMapping("/{reservationId}")
    suspend fun releaseReservation(
        @PathVariable reservationId: String
    ): ResponseEntity<Void> {
        reservationService.releaseReservation(reservationId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Handles requests to confirm an active reservation for an order.
     */
    @PutMapping("/{reservationId}/confirmation")
    suspend fun confirmReservation(
        @PathVariable reservationId: String,
        @RequestBody request: ReservationConfirmationRequest
    ): ResponseEntity<ReservationResponse> {
        val response = reservationService.confirmReservation(reservationId, request.orderId)
        return ResponseEntity.ok(response)
    }

    /**
     * Handles requests to refund a confirmed reservation, making seats available again.
     */
    @PostMapping("/{reservationId}/refund")
    suspend fun refundReservation(
        @PathVariable reservationId: String,
        @RequestBody request: ReservationRefundRequest
    ): ResponseEntity<ReservationResponse> {
        val response = reservationService.refundReservation(reservationId, request.orderId)
        return ResponseEntity.ok(response)
    }
}
