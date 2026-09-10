package it.polito.cpo.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.r2dbc.postgresql.codec.Json
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationResponse
import it.polito.cpo.contracts.inventory.ReservationStatus
import it.polito.cpo.fakes.FakeIdempotencyRecordRepository
import it.polito.cpo.fakes.FakeReservationRepository
import it.polito.cpo.model.IdempotencyRecord
import it.polito.cpo.model.Reservation
import it.polito.cpo.observability.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class IdempotencyServiceTest {

    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `generateFingerprint produces identical hash for reordered and duplicate seats`() {
        val idempotencyService = IdempotencyService(FakeIdempotencyRecordRepository(), FakeReservationRepository(), mapper, SimpleMeterRegistry())
        
        val orderId = UUID.randomUUID()
        val req1 = ReservationRequest(orderId, listOf("1A", "1B", "1C"), "route-1", LocalDate.now())
        val req2 = ReservationRequest(orderId, listOf("1C", "1A", "1B"), "route-1", LocalDate.now())
        val req3 = ReservationRequest(orderId, listOf("1B", "1A", "1C", "1A", "1B"), "route-1", LocalDate.now())
        
        val fp1 = idempotencyService.generateFingerprint(req1)
        val fp2 = idempotencyService.generateFingerprint(req2)
        val fp3 = idempotencyService.generateFingerprint(req3)

        assertEquals(fp1, fp2)
        assertEquals(fp1, fp3)
    }

    @Test
    fun `getCachedResponse returns cached response if idempotency key and fingerprint match`() = runTest {
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val idempotencyService = IdempotencyService(idemRepo, resRepo, mapper, SimpleMeterRegistry())

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1", LocalDate.now())
        val principalId = "user-1"
        val idempotencyKey = "key-cached"
        
        val fingerprint = idempotencyService.generateFingerprint(request)

        val cachedResponse = ReservationResponse(
            "res-cached", 
            ReservationStatus.HELD, 
            OffsetDateTime.now().plusMinutes(10)
        )
        
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = fingerprint,
            responseStatusCode = 201,
            responseBody = Json.of(mapper.writeValueAsString(cachedResponse))
        ))

        val response = idempotencyService.getCachedResponse(idempotencyKey, principalId, request)
        
        assertEquals("res-cached", response?.reservationId)
        assertEquals(ReservationStatus.HELD, response?.status)
    }

    @Test
    fun `getCachedResponse throws 409 if reservation expired in DB`() = runTest {
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val idempotencyService = IdempotencyService(idemRepo, resRepo, mapper, SimpleMeterRegistry())

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1", LocalDate.now())
        val principalId = "user-1"
        val idempotencyKey = "key-cached-expired"
        
        val fingerprint = idempotencyService.generateFingerprint(request)

        val reservationId = "res-expired"
        val cachedResponse = ReservationResponse(
            reservationId,
            ReservationStatus.HELD,
            OffsetDateTime.now().plusMinutes(10)
        )
        
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = fingerprint,
            responseStatusCode = 201,
            responseBody = Json.of(mapper.writeValueAsString(cachedResponse))
        ))

        // Put EXPIRED reservation in DB
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = "EXPIRED",
            expiresAt = OffsetDateTime.now().minusMinutes(5)
        ))

        val exception = assertThrows<ApiException> {
            idempotencyService.getCachedResponse(idempotencyKey, principalId, request)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_EXPIRED", exception.code)
    }

    @Test
    fun `getCachedResponse throws 409 if reservation cancelled in DB`() = runTest {
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val idempotencyService = IdempotencyService(idemRepo, resRepo, mapper, SimpleMeterRegistry())

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1", LocalDate.now())
        val principalId = "user-1"
        val idempotencyKey = "key-cached-cancelled"
        
        val fingerprint = idempotencyService.generateFingerprint(request)

        val reservationId = "res-cancelled"
        val cachedResponse = ReservationResponse(
            reservationId, 
            ReservationStatus.HELD, 
            OffsetDateTime.now().plusMinutes(10)
        )
        
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = fingerprint,
            responseStatusCode = 201,
            responseBody = Json.of(mapper.writeValueAsString(cachedResponse))
        ))

        // Put CANCELLED reservation in DB
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = "CANCELLED",
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        val exception = assertThrows<ApiException> {
            idempotencyService.getCachedResponse(idempotencyKey, principalId, request)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_CANCELLED", exception.code)
    }

    @Test
    fun `getCachedResponse throws 409 if payload is different`() = runTest {
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val idempotencyService = IdempotencyService(idemRepo, resRepo, mapper, SimpleMeterRegistry())

        val originalRequest = ReservationRequest(UUID.randomUUID(), listOf("1A"), "route-1", LocalDate.now())
        val newRequest = ReservationRequest(UUID.randomUUID(), listOf("1B"), "route-1", LocalDate.now())
        
        val principalId = "user-1"
        val idempotencyKey = "key-conflict"
        
        val originalFingerprint = idempotencyService.generateFingerprint(originalRequest)

        idemRepo.save(IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = originalFingerprint,
            responseStatusCode = 201,
            responseBody = null
        ))

        val exception = assertThrows<ApiException> {
            idempotencyService.getCachedResponse(idempotencyKey, principalId, newRequest)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("IDEMPOTENCY_CONFLICT", exception.code)
    }
}
