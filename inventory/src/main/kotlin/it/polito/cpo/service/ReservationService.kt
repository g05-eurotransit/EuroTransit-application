package it.polito.cpo.service

import io.r2dbc.postgresql.codec.Json
import tools.jackson.databind.ObjectMapper
import it.polito.cpo.contracts.events.InventoryFailedEvent
import it.polito.cpo.contracts.events.InventoryFailedPayload
import it.polito.cpo.contracts.events.InventoryReservedEvent
import it.polito.cpo.contracts.events.InventoryReservedPayload
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationResponse
import it.polito.cpo.contracts.inventory.ReservationStatus
import it.polito.cpo.model.IdempotencyRecord
import it.polito.cpo.model.Reservation
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.IdempotencyRecordRepository
import it.polito.cpo.repository.ReservationRepository
import it.polito.cpo.repository.SeatRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID
import it.polito.cpo.model.OutboxEvent
import it.polito.cpo.repository.OutboxEventRepository
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import kotlinx.coroutines.flow.toList

import it.polito.cpo.service.IdempotencyService

@Service
class ReservationService(
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    private val idempotencyService: IdempotencyService,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val meterRegistry: MeterRegistry,
    // T2 (ADR-016): how long the hold runs after the customer pays. Defaulted here and in no deployment
    // value, so the configuration repository does not change; T1 stays the ten minutes below.
    @Value("\${inventory.reservations.payment-window-minutes:10}")
    private val paymentWindowMinutes: Long = 10,
) {
    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    /**
     * Holds the requested seats, creates the reservation record, outbox event, and idempotency entry.
     */
    suspend fun reserveSeats(
        idempotencyKey: String,
        principalId: String,
        correlationId: String,
        request: ReservationRequest
    ): ReservationResponse {
        
        val cachedResponse = idempotencyService.getCachedResponse(idempotencyKey, principalId, request)
        if (cachedResponse != null) {
            return cachedResponse
        }

        // Increment only for logical attempts, not cached retries
        meterRegistry.counter("inventory.holds.attempted").increment()

        val reservationId = UUID.randomUUID().toString()

        try {
            val response = transactionalOperator.executeAndAwait {
                val dbNow = reservationRepository.getCurrentTimestamp()
                val expiresAt = dbNow.plusMinutes(10)

                val distinctSeats = request.seats.distinct()
                val rowsUpdated = if (distinctSeats.isEmpty()) {
                    0
                } else {
                    seatRepository.holdSeats(request.routeId, request.travelDate, distinctSeats, reservationId, distinctSeats.size)
                }
                val success = distinctSeats.isNotEmpty() && rowsUpdated == distinctSeats.size
                val status = if (success) ReservationStatus.HELD else ReservationStatus.FAILED

                if (success) {
                    val reservation = Reservation(
                        reservationId = reservationId,
                        orderId = request.orderId,
                        routeId = request.routeId,
                        travelDate = request.travelDate,
                        status = status.name,
                        expiresAt = expiresAt
                    )
                    reservationRepository.save(reservation)
                }

                val storedResponse = ReservationResponse(
                    reservationId = reservationId,
                    status = status,
                    expiresAt = expiresAt
                )

                idempotencyService.createRecord(idempotencyKey, principalId, request, storedResponse, success)

                // 2. Save Outbox Event inside the transaction
                if (success) {
                    logger.info("Successfully reserved seats for order: {} by principal: {}", request.orderId, principalId)
                    val event = InventoryReservedEvent(
                        correlationId = correlationId,
                        orderId = request.orderId,
                        principalId = principalId,
                        payload = InventoryReservedPayload(
                            reservationId = reservationId,
                            routeId = request.routeId,
                            travelDate = request.travelDate,
                            expiresAt = expiresAt
                        )
                    )
                    val outboxEvent = OutboxEvent(
                        aggregateType = "Inventory",
                        aggregateId = request.orderId.toString(),
                        type = "inventory-reserved",
                        payload = Json.of(objectMapper.writeValueAsString(event))
                    )
                    outboxEventRepository.save(outboxEvent)
                } else {
                    logger.info("Failed to reserve seats for order: {} by principal: {}", request.orderId, principalId)
                    val event = InventoryFailedEvent(
                        correlationId = correlationId,
                        orderId = request.orderId,
                        principalId = principalId,
                        payload = InventoryFailedPayload(
                            reason = "Seats unavailable or already held",
                            travelDate = request.travelDate
                        )
                    )
                    val outboxEvent = OutboxEvent(
                        aggregateType = "Inventory",
                        aggregateId = request.orderId.toString(),
                        type = "inventory-failed",
                        payload = Json.of(objectMapper.writeValueAsString(event))
                    )
                    outboxEventRepository.save(outboxEvent)
                }

                storedResponse
            }

            if (response.status == ReservationStatus.HELD) {
                meterRegistry.counter("inventory.holds.successful").increment()
            } else {
                meterRegistry.counter("inventory.holds.rejected").increment()
            }

            return response
        } catch (_: DuplicateKeyException) {
            val cachedFallback = idempotencyService.getCachedResponseFromFallback(idempotencyKey, principalId, request)
            if (cachedFallback != null) {
                return cachedFallback
            }

            val existingReservation = reservationRepository.findByOrderId(request.orderId)
            if (existingReservation != null) {
                logger.warn("Reservation already exists for order: {} (status: {}). Handled DuplicateKeyException on order_id constraint.", request.orderId, existingReservation.status)
                throw ApiException(
                    status = HttpStatus.CONFLICT,
                    code = "ORDER_ALREADY_RESERVED",
                    message = "A reservation already exists for order ${request.orderId} (status: ${existingReservation.status})",
                    details = mapOf(
                        "reservationId" to existingReservation.reservationId,
                        "status" to existingReservation.status
                    )
                )
            }

            throw ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Idempotency record lost after DuplicateKeyException"
            )
        }
    }

    /**
     * Confirms a held reservation and transitions its seats to sold.
     */
    suspend fun confirmReservation(reservationId: String, orderId: UUID): ReservationResponse {
        var apiExceptionToThrow: ApiException? = null
        var isNewlyConfirmed = false

        val response = transactionalOperator.executeAndAwait {
            val reservation = reservationRepository.findByReservationIdForUpdate(reservationId) ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                "RESERVATION_NOT_FOUND",
                "Reservation not found"
            )

            if (reservation.orderId != orderId) {
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_ORDER_CONFLICT", "Reservation belongs to another order")
            }

            if (reservation.status == ReservationStatus.CONFIRMED.name) {
                return@executeAndAwait ReservationResponse(
                    reservationId,
                    ReservationStatus.CONFIRMED,
                    reservation.expiresAt
                )
            }

            if (reservation.status == ReservationStatus.EXPIRED.name) {
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "Reservation has expired")
            }

            if (reservation.status == ReservationStatus.CANCELLED.name) {
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_CANCELLED", "Reservation was cancelled")
            }

            // It is HELD. Verify expiration in real-time with the DB clock
            if (!reservation.expiresAt.isAfter(reservationRepository.getCurrentTimestamp())) {
                val expiredReservation = reservation.copy(status = "EXPIRED", isNewRecord = false)
                reservationRepository.save(expiredReservation)
                seatRepository.releaseSeats(reservationId)
                apiExceptionToThrow = ApiException(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "Reservation has expired")
                return@executeAndAwait null
            }

            val expectedSeats = seatRepository.countByReservationId(reservationId)
            val updatedSeats = seatRepository.confirmSeats(reservationId)

            if (updatedSeats.toLong() != expectedSeats) {
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_SEAT_CONFLICT", "Seat count mismatch during confirmation")
            }

            val confirmedReservation = reservation.copy(status = "CONFIRMED", isNewRecord = false)
            reservationRepository.save(confirmedReservation)
            isNewlyConfirmed = true

            ReservationResponse(
                reservationId,
                ReservationStatus.CONFIRMED,
                reservation.expiresAt
            )
        }

        if (apiExceptionToThrow != null) {
            meterRegistry.counter("inventory.holds.expired").increment()
            throw apiExceptionToThrow
        }

        if (isNewlyConfirmed) {
            meterRegistry.counter("inventory.holds.confirmed").increment()
        }

        return response!!
    }

    /**
     * Opens the one-time T2 payment window of a customer-hold reservation (ADR-016).
     *
     * Same shape as [confirmReservation], and for the same reason: the row lock is what serializes
     * this with confirmation, release and the expiration sweep, so the "T1 still valid" check and the
     * extension below are one decision rather than a read followed by a write somebody else can slip
     * between. The expiry is evaluated before the replay check on purpose: a window that has already
     * run out is as expired as a T1 that did, and answering `HELD` for it would send Orders to
     * authorize against a seat that is about to be swept.
     *
     * Idempotent by state: once `paymentWindowStartedAt` is set, every replay returns the stored
     * deadline and never extends it again (invariant 9).
     */
    suspend fun startPaymentWindow(reservationId: String, orderId: UUID): ReservationResponse {
        var apiExceptionToThrow: ApiException? = null
        var outcome = "replayed"

        val response = transactionalOperator.executeAndAwait {
            val reservation = reservationRepository.findByReservationIdForUpdate(reservationId) ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                "RESERVATION_NOT_FOUND",
                "Reservation not found"
            )

            if (reservation.orderId != orderId) {
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_ORDER_CONFLICT", "Reservation belongs to another order")
            }

            when (reservation.status) {
                ReservationStatus.EXPIRED.name ->
                    throw ApiException(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "Reservation has expired")
                ReservationStatus.CANCELLED.name ->
                    throw ApiException(HttpStatus.CONFLICT, "RESERVATION_CANCELLED", "Reservation was cancelled")
                ReservationStatus.HELD.name -> Unit
                // CONFIRMED or REFUNDED: an order still waiting to pay cannot own a sold seat, so this is
                // not a business answer Orders should act on. A distinct code keeps it from ever being
                // mistaken for an expiry, which would fail the order as if no money had moved.
                else -> throw ApiException(
                    HttpStatus.CONFLICT,
                    "RESERVATION_NOT_HELD",
                    "Reservation is ${reservation.status}, not HELD",
                )
            }

            val dbNow = reservationRepository.getCurrentTimestamp()
            if (!reservation.expiresAt.isAfter(dbNow)) {
                reservationRepository.save(reservation.copy(status = ReservationStatus.EXPIRED.name, isNewRecord = false))
                seatRepository.releaseSeats(reservationId)
                apiExceptionToThrow = ApiException(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "Reservation has expired")
                return@executeAndAwait null
            }

            if (reservation.paymentWindowStartedAt != null) {
                return@executeAndAwait ReservationResponse(reservationId, ReservationStatus.HELD, reservation.expiresAt)
            }

            val extended = reservation.copy(
                expiresAt = dbNow.plusMinutes(paymentWindowMinutes),
                paymentWindowStartedAt = dbNow,
                isNewRecord = false,
            )
            reservationRepository.save(extended)
            outcome = "opened"
            ReservationResponse(reservationId, ReservationStatus.HELD, extended.expiresAt)
        }

        if (apiExceptionToThrow != null) {
            meterRegistry.counter("inventory.holds.expired").increment()
            meterRegistry.counter("inventory.holds.payment_window", "result", "expired").increment()
            throw apiExceptionToThrow
        }

        // After the commit, so a rolled-back extension is never counted as opened.
        meterRegistry.counter("inventory.holds.payment_window", "result", outcome).increment()
        return response!!
    }

    /**
     * Refunds a confirmed reservation and transitions its seats back to available.
     * This is a dedicated post-sale transition (SOLD -> AVAILABLE).
     */
    suspend fun refundReservation(reservationId: String, orderId: UUID): ReservationResponse {
        var isNewlyRefunded = false

        val response = transactionalOperator.executeAndAwait {
            val reservation = reservationRepository.findByReservationIdForUpdate(reservationId) ?: throw ApiException(
                HttpStatus.NOT_FOUND,
                "RESERVATION_NOT_FOUND",
                "Reservation not found"
            )

            if (reservation.orderId != orderId) {
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_ORDER_CONFLICT", "Reservation belongs to another order")
            }

            if (reservation.status == ReservationStatus.REFUNDED.name) {
                return@executeAndAwait ReservationResponse(
                    reservationId,
                    ReservationStatus.REFUNDED,
                    reservation.expiresAt
                )
            }

            if (reservation.status != ReservationStatus.CONFIRMED.name) {
                throw ApiException(
                    HttpStatus.CONFLICT, 
                    "RESERVATION_NOT_CONFIRMED", 
                    "Only confirmed reservations can be refunded (current status: ${reservation.status})"
                )
            }

            val expectedSeats = seatRepository.countByReservationId(reservationId)
            val updatedSeats = seatRepository.refundSeats(reservationId)

            if (updatedSeats.toLong() != expectedSeats) {
                throw ApiException(HttpStatus.CONFLICT, "RESERVATION_SEAT_CONFLICT", "Seat count mismatch during refund")
            }

            val refundedReservation = reservation.copy(status = ReservationStatus.REFUNDED.name, isNewRecord = false)
            reservationRepository.save(refundedReservation)
            isNewlyRefunded = true

            ReservationResponse(
                reservationId,
                ReservationStatus.REFUNDED,
                reservation.expiresAt
            )
        }

        if (isNewlyRefunded) {
            meterRegistry.counter("inventory.holds.refunded").increment()
        }

        return response!!
    }

    /**
     * Cancels a held reservation and releases its associated seats.
     */
    suspend fun releaseReservation(reservationId: String) {
        var released = false
        transactionalOperator.executeAndAwait {
            val rowsUpdated = reservationRepository.cancelIfHeld(reservationId)
            
            if (rowsUpdated > 0) {
                logger.info("Releasing seats for cancelled reservation: {}", reservationId)
                seatRepository.releaseSeats(reservationId)
                released = true
            } else {
                logger.info("Ignored release request for reservation: {} (not found, already cancelled, or not HELD)", reservationId)
            }
        }
        if (released) {
            meterRegistry.counter("inventory.holds.released").increment()
        }
    }

}
