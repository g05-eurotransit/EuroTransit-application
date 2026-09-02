package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import io.r2dbc.postgresql.codec.Json
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationResponse
import it.polito.cpo.contracts.inventory.ReservationStatus
import it.polito.cpo.model.IdempotencyRecord
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.IdempotencyRecordRepository
import it.polito.cpo.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

@Service
class IdempotencyService(
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val reservationRepository: ReservationRepository,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(IdempotencyService::class.java)

    fun generateFingerprint(request: ReservationRequest): String {
        val canonicalRequest = request.copy(seats = request.seats.distinct().sorted())
        val json = objectMapper.writeValueAsString(canonicalRequest)
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun getCachedResponse(idempotencyKey: String, principalId: String, request: ReservationRequest): ReservationResponse? {
        val fingerprint = generateFingerprint(request)
        val existingRecord = idempotencyRecordRepository.findByKeyAndPrincipal(idempotencyKey, principalId)
        if (existingRecord != null) {
            return processExistingRecord(existingRecord, fingerprint, idempotencyKey)
        }
        return null
    }

    suspend fun getCachedResponseFromFallback(idempotencyKey: String, principalId: String, request: ReservationRequest): ReservationResponse? {
        val fingerprint = generateFingerprint(request)
        val concurrentRecord = idempotencyRecordRepository.findByKeyAndPrincipal(idempotencyKey, principalId)
        if (concurrentRecord != null) {
            logger.info("Concurrent request detected for idempotency key: {}. Falling back to cached response.", idempotencyKey)
            return processExistingRecord(concurrentRecord, fingerprint, idempotencyKey)
        }
        return null
    }

    suspend fun createRecord(idempotencyKey: String, principalId: String, request: ReservationRequest, response: ReservationResponse, success: Boolean): IdempotencyRecord {
        val fingerprint = generateFingerprint(request)
        val idempotencyRecord = IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = fingerprint,
            responseStatusCode = if (success) 201 else 409,
            responseBody = Json.of(objectMapper.writeValueAsString(response))
        )
        return idempotencyRecordRepository.save(idempotencyRecord)
    }

    private suspend fun processExistingRecord(
        existingRecord: IdempotencyRecord,
        fingerprint: String,
        idempotencyKey: String
    ): ReservationResponse {
        if (existingRecord.requestFingerprint != fingerprint) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = "IDEMPOTENCY_CONFLICT",
                message = "Idempotency key reused with a different payload"
            )
        }

        meterRegistry.counter("inventory.idempotency.hits").increment()
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
}
