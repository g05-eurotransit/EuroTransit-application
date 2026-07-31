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
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import it.polito.cpo.model.OutboxEvent
import it.polito.cpo.repository.OutboxEventRepository
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import kotlinx.coroutines.flow.toList

@Service
class ReservationService(
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val meterRegistry: MeterRegistry,
    private val clock: java.time.Clock
) {
    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    /**
     * Generates an SHA-256 fingerprint of the request payload to detect idempotency collisions.
     */
    private fun generateFingerprint(request: ReservationRequest): String {
        // Canonize the seats order to prevent false conflicts
        val canonicalRequest = request.copy(seats = request.seats.sorted())
        val json = objectMapper.writeValueAsString(canonicalRequest)
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Reserves seats and handles idempotency to prevent oversell or double-processing.
     * DB writes and Outbox events are executed atomically in a programmatic transaction.
     */
    suspend fun reserveSeats(
        idempotencyKey: String,
        principalId: String,
        correlationId: String,
        request: ReservationRequest
    ): ReservationResponse {
        
        val fingerprint = generateFingerprint(request)
        
        // Fast-path check
        val existingRecord = idempotencyRecordRepository.findByKeyAndPrincipal(idempotencyKey, principalId)
        if (existingRecord != null) {
            return processExistingRecord(existingRecord, fingerprint, idempotencyKey)
        }

        // Increment only for logical attempts, not cached retries
        meterRegistry.counter("inventory.holds.attempted").increment()

        val reservationId = UUID.randomUUID().toString()
        val expiresAt = OffsetDateTime.now(clock).plusMinutes(10)

        try {
            val isSuccess = transactionalOperator.executeAndAwait {
                val rowsUpdated = seatRepository.holdSeats(request.routeId, request.seats, reservationId)
                val success = rowsUpdated == request.seats.size
                val status = if (success) ReservationStatus.HELD else ReservationStatus.FAILED

                if (success) {
                    val reservation = Reservation(
                        reservationId = reservationId,
                        orderId = request.orderId,
                        routeId = request.routeId,
                        status = status.name,
                        expiresAt = expiresAt
                    )
                    reservationRepository.save(reservation)
                }

                val storedResponse = ReservationResponse(
                    reservationId = reservationId,
                    status = status,
                    expiresAt = expiresAt.toLocalDateTime()
                )

                val idempotencyRecord = IdempotencyRecord(
                    idempotencyKey = idempotencyKey,
                    principalId = principalId,
                    operation = "RESERVE",
                    requestFingerprint = fingerprint,
                    responseStatusCode = if (success) 201 else 409,
                    responseBody = Json.of(objectMapper.writeValueAsString(storedResponse))
                )
                idempotencyRecordRepository.save(idempotencyRecord)

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
                            expiresAt = expiresAt.toLocalDateTime()
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
                            reason = "Seats unavailable or already held"
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

                success
            }

            if (isSuccess) {
                meterRegistry.counter("inventory.holds.successful").increment()
            } else {
                meterRegistry.counter("inventory.holds.rejected").increment()
            }

            val finalStatus = if (isSuccess) ReservationStatus.HELD else ReservationStatus.FAILED
            return ReservationResponse(
                reservationId = reservationId,
                status = finalStatus,
                expiresAt = expiresAt.toLocalDateTime()
            )
        } catch (_: DuplicateKeyException) {
            logger.info("Concurrent request detected for idempotency key: {}. Falling back to cached response.", idempotencyKey)
            val concurrentRecord = idempotencyRecordRepository.findByKeyAndPrincipal(idempotencyKey, principalId)
                ?: throw ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "Idempotency record lost after DuplicateKeyException"
                )
            
            return processExistingRecord(concurrentRecord, fingerprint, idempotencyKey)
        }
    }

    private suspend fun processExistingRecord(
        existingRecord: IdempotencyRecord,
        fingerprint: String,
        idempotencyKey: String
    ): ReservationResponse {
        meterRegistry.counter("inventory.idempotency.hits").increment()
        
        if (existingRecord.requestFingerprint != fingerprint) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = "IDEMPOTENCY_CONFLICT",
                message = "Idempotency key reused with a different payload"
            )
        }
        if (existingRecord.responseBody != null) {
            logger.info("Returning cached reservation response for idempotency key: {}", idempotencyKey)
            val response = objectMapper.readValue(
                existingRecord.responseBody.asString(),
                ReservationResponse::class.java
            )

            if (response.status == ReservationStatus.HELD) {
                val currentReservation = reservationRepository.findByReservationId(response.reservationId)
                if (currentReservation != null) {
                    if (currentReservation.status == "EXPIRED") {
                        logger.warn("Idempotency hit for reservation {} but it has expired in DB.", response.reservationId)
                        throw ApiException(
                            status = HttpStatus.CONFLICT,
                            code = "RESERVATION_EXPIRED",
                            message = "The reservation hold has expired"
                        )
                    }
                    if (currentReservation.status == "CANCELLED") {
                        logger.warn("Idempotency hit for reservation {} but it was cancelled in DB.", response.reservationId)
                        throw ApiException(
                            status = HttpStatus.CONFLICT,
                            code = "RESERVATION_CANCELLED",
                            message = "The reservation hold was cancelled"
                        )
                    }
                    if (currentReservation.status == "CONFIRMED") {
                        logger.info("Idempotency hit for reservation {} but it is already CONFIRMED in DB.", response.reservationId)
                        return ReservationResponse(
                            reservationId = currentReservation.reservationId,
                            status = ReservationStatus.CONFIRMED,
                            expiresAt = currentReservation.expiresAt.toLocalDateTime()
                        )
                    }
                    if (currentReservation.status == "HELD" && !currentReservation.expiresAt.isAfter(reservationRepository.getCurrentTimestamp())) {
                        logger.warn("Idempotency hit for reservation {} but it has logically expired.", response.reservationId)
                        throw ApiException(
                            status = HttpStatus.CONFLICT,
                            code = "RESERVATION_EXPIRED",
                            message = "The reservation hold has expired"
                        )
                    }
                }
            }
            
            return response
        }
        throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Idempotency record in invalid state")
    }

    /**
     * Atomically confirms a held reservation and its seats after a successful payment.
     * Uses natural idempotency and row-level locking to prevent expiration conflicts.
     */
    suspend fun confirmReservation(reservationId: String, orderId: UUID): ReservationResponse {
        var apiExceptionToThrow: ApiException? = null

        val response = transactionalOperator.executeAndAwait {
            val reservation = reservationRepository.findByReservationIdForUpdate(reservationId)
            
            if (reservation == null) {
                apiExceptionToThrow = ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reservation not found")
                return@executeAndAwait null
            }

            if (reservation.orderId != orderId) {
                apiExceptionToThrow = ApiException(HttpStatus.CONFLICT, "RESERVATION_ORDER_CONFLICT", "Reservation belongs to another order")
                return@executeAndAwait null
            }

            if (reservation.status == ReservationStatus.CONFIRMED.name) {
                return@executeAndAwait ReservationResponse(
                    reservationId,
                    ReservationStatus.CONFIRMED,
                    reservation.expiresAt.toLocalDateTime()
                )
            }

            if (reservation.status == ReservationStatus.EXPIRED.name) {
                apiExceptionToThrow = ApiException(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "Reservation has expired")
                return@executeAndAwait null
            }

            if (reservation.status == ReservationStatus.CANCELLED.name) {
                apiExceptionToThrow = ApiException(HttpStatus.CONFLICT, "RESERVATION_CANCELLED", "Reservation was cancelled")
                return@executeAndAwait null
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
                apiExceptionToThrow = ApiException(HttpStatus.CONFLICT, "RESERVATION_SEAT_CONFLICT", "Seat count mismatch during confirmation")
                return@executeAndAwait null
            }

            val confirmedReservation = reservation.copy(status = "CONFIRMED", isNewRecord = false)
            reservationRepository.save(confirmedReservation)

            ReservationResponse(
                reservationId,
                ReservationStatus.CONFIRMED,
                reservation.expiresAt.toLocalDateTime()
            )
        }

        if (apiExceptionToThrow != null) {
            throw apiExceptionToThrow
        }

        return response!!
    }

    /**
     * Releases a reservation and frees up the associated seats.
     * This operation is naturally idempotent.
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

    /**
     * Sweeps the database for reservations that have exceeded their hold period
     * without being converted to a paid order. Releases the associated seats back to the
     * pool and marks the reservation as EXPIRED. This operation is essential to
     * maintain inventory correctness and prevent permanent oversell locks.
     */
    suspend fun sweepExpiredReservations(): Int {
        var count = 0
        try {
            transactionalOperator.executeAndAwait {
                val expiredReservations = reservationRepository.expireAndReturn().toList()
                for ((reservationId) in expiredReservations) {
                    logger.info("Reservation {} atomically expired. Releasing seats.", reservationId)
                    seatRepository.releaseSeats(reservationId)
                    count++
                }
            }
            if (count > 0) {
                meterRegistry.counter("inventory.holds.expired").increment(count.toDouble())
            }
        } catch (e: Exception) {
            logger.error("Failed during atomic sweepExpiredReservations", e)
        }
        return count
    }

    /**
     * Purges idempotency records that are older than the specified cutoff time.
     * Enforces the retention policy (30 minutes) defined in the architecture decisions,
     * ensuring the database table does not grow indefinitely.
     */
    suspend fun purgeExpiredIdempotency(cutoff: OffsetDateTime): Int {
        return idempotencyRecordRepository.deleteByCreatedAtBefore(cutoff)
    }
}
