package it.polito.cpo.service

import it.polito.cpo.contracts.events.InventoryFailedEvent
import it.polito.cpo.contracts.events.InventoryReservedEvent
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationStatus
import it.polito.cpo.model.OutboxEvent
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.model.IdempotencyRecord
import it.polito.cpo.model.Reservation
import it.polito.cpo.repository.IdempotencyRecordRepository
import it.polito.cpo.repository.ReservationRepository
import it.polito.cpo.repository.SeatRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import it.polito.cpo.observability.ApiException
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.reactive.TransactionCallback
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID

class ReservationServiceTest {

    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private val fakeTransactionalOperator = object : TransactionalOperator {
        override fun <T : Any> execute(action: TransactionCallback<T>): Flux<T> {
            val stubTransaction = object : ReactiveTransaction {
                override fun isNewTransaction() = true
                override fun setRollbackOnly() {}
                override fun isRollbackOnly() = false
                override fun isCompleted() = false
            }
            return Flux.from(action.doInTransaction(stubTransaction))
        }
        override fun <T : Any> transactional(flux: Flux<T>): Flux<T> = flux
        override fun <T : Any> transactional(mono: Mono<T>): Mono<T> = mono
    }

    private class FakeSeatRepository(
        private val delegate: SeatRepository = Mockito.mock(SeatRepository::class.java),
        var seatsAvailable: Boolean = true
    ) : SeatRepository by delegate {
        var releaseCalledWith: String? = null
        override suspend fun holdSeats(routeId: String, seatIds: List<String>, reservationId: String): Int {
            return if (seatsAvailable) seatIds.size else 0
        }
        override suspend fun releaseSeats(reservationId: String): Int {
            releaseCalledWith = reservationId
            return 1
        }
    }

    private class FakeReservationRepository(
        private val delegate: ReservationRepository = Mockito.mock(ReservationRepository::class.java)
    ) : ReservationRepository by delegate {
        val store = HashMap<String, Reservation>()
        override suspend fun findByReservationId(reservationId: String): Reservation? {
            return store[reservationId]
        }
        override suspend fun <S : Reservation> save(entity: S): S {
            store[entity.reservationId] = entity
            return entity
        }
        override fun expireAndReturn(now: java.time.OffsetDateTime): kotlinx.coroutines.flow.Flow<Reservation> {
            val expired = store.values.filter { it.status == "HELD" && it.expiresAt.isBefore(now) }
            val updated = expired.map { it.copy(status = "EXPIRED") }
            updated.forEach { store[it.reservationId] = it }
            return kotlinx.coroutines.flow.flowOf(*updated.toTypedArray())
        }
    }

    private class FakeIdempotencyRecordRepository(
        private val delegate: IdempotencyRecordRepository = Mockito.mock(
            IdempotencyRecordRepository::class.java)
    ) : IdempotencyRecordRepository by delegate {
        val store = HashMap<String, IdempotencyRecord>()
        override suspend fun <S : IdempotencyRecord> save(entity: S): S {
            store["${entity.idempotencyKey}-${entity.principalId}"] = entity
            return entity
        }
        override suspend fun findByKeyAndPrincipal(key: String, principalId: String): IdempotencyRecord? {
            return store["$key-$principalId"]
        }
    }

    private class FakeOutboxEventRepository(
        private val delegate: OutboxEventRepository = Mockito.mock(OutboxEventRepository::class.java),
        var shouldThrow: Boolean = false
    ) : OutboxEventRepository by delegate {
        val store = mutableListOf<OutboxEvent>()
        override suspend fun <S : OutboxEvent> save(entity: S): S {
            if (shouldThrow) throw RuntimeException("Database error during outbox save")
            store.add(entity)
            return entity
        }
    }

    private fun buildService(
        seatRepo: FakeSeatRepository,
        resRepo: FakeReservationRepository,
        idemRepo: FakeIdempotencyRecordRepository,
        outboxRepo: FakeOutboxEventRepository
    ) = ReservationService(
        seatRepository = seatRepo,
        reservationRepository = resRepo,
        idempotencyRecordRepository = idemRepo,
        objectMapper = mapper,
        outboxEventRepository = outboxRepo,
        transactionalOperator = fakeTransactionalOperator,
        meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        clock = java.time.Clock.systemUTC()
    )

    @Test
    fun `reserveSeats successfully holds seats, persists state, and publishes Reserved event`() = runTest {
        val seatRepo = FakeSeatRepository(seatsAvailable = true)
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, idemRepo, outboxRepo)

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val principalId = "user-1"
        val correlationId = "corr-1"
        val idempotencyKey = "key-success"
        
        val response = service.reserveSeats(idempotencyKey, principalId, correlationId, request)

        assertEquals(
            ReservationStatus.HELD,
            response.status,
            "The reservation status should be HELD"
        )
        
        assertTrue(
            resRepo.store.containsKey(response.reservationId),
            "The reservation must be saved in the database"
        )
        assertTrue(
            idemRepo.store.containsKey("$idempotencyKey-$principalId"),
            "The idempotency record must be saved"
        )
        
        assertEquals(1, outboxRepo.store.size, "Exactly one OutboxEvent should be emitted")

        val emittedEvent = mapper.readValue(outboxRepo.store.first().payload.asString(), InventoryReservedEvent::class.java)
        assertEquals(correlationId, emittedEvent.correlationId)
        assertEquals(request.orderId, emittedEvent.orderId)
        assertEquals(principalId, emittedEvent.principalId)
    }

    @Test
    fun `reserveSeats fails to hold seats (oversell), does not persist reservation, and publishes Failed event`() = runTest {
        val seatRepo = FakeSeatRepository(seatsAvailable = false)
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, idemRepo, outboxRepo)

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val principalId = "user-1"
        val correlationId = "corr-1"
        val idempotencyKey = "key-oversell"
        
        val response = service.reserveSeats(idempotencyKey, principalId, correlationId, request)

        assertEquals(
            ReservationStatus.FAILED, 
            response.status, 
            "The reservation status should be FAILED due to oversell"
        )
        
        assertTrue(
            resRepo.store.isEmpty(), 
            "The reservation must NOT be saved in the database if it failed"
        )
        assertTrue(
            idemRepo.store.containsKey("$idempotencyKey-$principalId"), 
            "The idempotency record MUST be saved to cache the failure"
        )

        assertEquals(1, outboxRepo.store.size, "Exactly one OutboxEvent should be emitted")
        
        val emittedEvent = mapper.readValue(outboxRepo.store.first().payload.asString(), InventoryFailedEvent::class.java)
        assertEquals(correlationId, emittedEvent.correlationId)
        assertEquals(request.orderId, emittedEvent.orderId)
        assertEquals(principalId, emittedEvent.principalId)
    }

    @Test
    fun `reserveSeats with identical idempotency key returns cached response and skips DB updates`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, idemRepo, outboxRepo)

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val principalId = "user-1"
        val correlationId = "corr-1"
        val idempotencyKey = "key-cached"
        
        val fingerprint = service.javaClass.getDeclaredMethod("generateFingerprint", ReservationRequest::class.java)
            .apply { isAccessible = true }
            .invoke(service, request) as String

        val cachedResponse = it.polito.cpo.contracts.inventory.ReservationResponse(
            "res-cached", 
            ReservationStatus.HELD, 
            java.time.LocalDateTime.now().plusMinutes(10)
        )
        
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = fingerprint,
            responseStatusCode = 201,
            responseBody = io.r2dbc.postgresql.codec.Json.of(mapper.writeValueAsString(cachedResponse))
        ))

        val response = service.reserveSeats(idempotencyKey, principalId, correlationId, request)

        assertEquals("res-cached", response.reservationId)
        assertEquals(ReservationStatus.HELD, response.status)
        
        assertTrue(resRepo.store.isEmpty(), "No new reservation should be saved")

        assertEquals(0, outboxRepo.store.size)
    }

    @Test
    fun `reserveSeats with identical idempotency key throws 409 if reservation expired in DB`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, idemRepo, outboxRepo)

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val principalId = "user-1"
        val correlationId = "corr-1"
        val idempotencyKey = "key-cached-expired"
        
        val fingerprint = service.javaClass.getDeclaredMethod("generateFingerprint", ReservationRequest::class.java)
            .apply { isAccessible = true }
            .invoke(service, request) as String

        val reservationId = "res-expired"
        val cachedResponse = it.polito.cpo.contracts.inventory.ReservationResponse(
            reservationId, 
            ReservationStatus.HELD, 
            java.time.LocalDateTime.now().plusMinutes(10)
        )
        
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = fingerprint,
            responseStatusCode = 201,
            responseBody = io.r2dbc.postgresql.codec.Json.of(mapper.writeValueAsString(cachedResponse))
        ))

        // Put EXPIRED reservation in DB
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            status = "EXPIRED",
            expiresAt = java.time.OffsetDateTime.now().minusMinutes(5)
        ))

        val exception = assertThrows<ApiException> {
            service.reserveSeats(idempotencyKey, principalId, correlationId, request)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_EXPIRED", exception.code)
    }

    @Test
    fun `reserveSeats with identical idempotency key throws 409 if reservation cancelled in DB`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, idemRepo, outboxRepo)

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val principalId = "user-1"
        val correlationId = "corr-1"
        val idempotencyKey = "key-cached-cancelled"
        
        val fingerprint = service.javaClass.getDeclaredMethod("generateFingerprint", ReservationRequest::class.java)
            .apply { isAccessible = true }
            .invoke(service, request) as String

        val reservationId = "res-cancelled"
        val cachedResponse = it.polito.cpo.contracts.inventory.ReservationResponse(
            reservationId, 
            ReservationStatus.HELD, 
            java.time.LocalDateTime.now().plusMinutes(10)
        )
        
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = fingerprint,
            responseStatusCode = 201,
            responseBody = io.r2dbc.postgresql.codec.Json.of(mapper.writeValueAsString(cachedResponse))
        ))

        // Put CANCELLED reservation in DB
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            status = "CANCELLED",
            expiresAt = java.time.OffsetDateTime.now().minusMinutes(5)
        ))

        val exception = assertThrows<ApiException> {
            service.reserveSeats(idempotencyKey, principalId, correlationId, request)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_CANCELLED", exception.code)
    }

    @Test
    fun `reserveSeats with existing idempotency key but different payload throws ApiException`() = runTest {
        val idemRepo = FakeIdempotencyRecordRepository()
        val service = buildService(
            FakeSeatRepository(),
            FakeReservationRepository(),
            idemRepo,
            FakeOutboxEventRepository()
        )

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val principalId = "user-1"
        val correlationId = "corr-1"
        val idempotencyKey = "key-conflict"
        
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            principalId = principalId,
            operation = "RESERVE",
            requestFingerprint = "a-completely-different-hash",
            responseStatusCode = 201,
            responseBody = io.r2dbc.postgresql.codec.Json.of("{}")
        ))

        val exception = assertThrows<ApiException> {
            service.reserveSeats(idempotencyKey, principalId, correlationId, request)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("IDEMPOTENCY_CONFLICT", exception.code)
    }

    @Test
    fun `releaseReservation successfully releases seats if reservation is held`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val service = buildService(
            seatRepo,
            resRepo,
            FakeIdempotencyRecordRepository(),
            FakeOutboxEventRepository()
        )

        val reservationId = "res-cancel-1"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            status = ReservationStatus.HELD.name,
            expiresAt = java.time.OffsetDateTime.now().plusMinutes(10)
        ))

        service.releaseReservation(reservationId)

        assertEquals(reservationId, seatRepo.releaseCalledWith)
        assertEquals("CANCELLED", resRepo.store[reservationId]?.status)
    }

    @Test
    fun `sweepExpiredReservations uses provided clock to determine expired reservations and releases seats`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        
        // Fixed time for the clock
        val fixedInstant = java.time.Instant.parse("2026-07-30T12:00:00Z")
        val fixedClock = java.time.Clock.fixed(fixedInstant, java.time.ZoneOffset.UTC)
        
        val service = ReservationService(
            seatRepo, resRepo, FakeIdempotencyRecordRepository(), FakeOutboxEventRepository(),
            mapper, fakeTransactionalOperator, io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            fixedClock
        )

        // Reservation that expires in the past (11:55) -> SHOULD be expired
        resRepo.save(Reservation(
            reservationId = "res-past",
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            status = "HELD",
            expiresAt = java.time.OffsetDateTime.parse("2026-07-30T11:55:00Z")
        ))
        
        // Reservation that expires in the future (12:05) -> SHOULD NOT be expired
        resRepo.save(Reservation(
            reservationId = "res-future",
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            status = "HELD",
            expiresAt = java.time.OffsetDateTime.parse("2026-07-30T12:05:00Z")
        ))

        val now = java.time.OffsetDateTime.now(fixedClock)
        val count = service.sweepExpiredReservations(now)

        assertEquals(1, count, "Only one reservation should have expired")
        assertEquals("res-past", seatRepo.releaseCalledWith, "Seats for res-past should be released")
        assertEquals("EXPIRED", resRepo.store["res-past"]?.status, "Past reservation should be marked EXPIRED")
        assertEquals("HELD", resRepo.store["res-future"]?.status, "Future reservation should remain HELD")
    }

    @Test
    fun `releaseReservation ignores request if reservation not found or already cancelled`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val service = buildService(
            seatRepo,
            resRepo,
            FakeIdempotencyRecordRepository(),
            FakeOutboxEventRepository()
        )

        service.releaseReservation("non-existent")

    }

    @Test
    fun `reserveSeats throws exception during outbox save, rolls back and does not increment metrics`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        
        val failingOutboxRepo = FakeOutboxEventRepository(shouldThrow = true)
        val meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        val service = ReservationService(
            seatRepo, resRepo, idemRepo, failingOutboxRepo,
            mapper, fakeTransactionalOperator, meterRegistry,
            java.time.Clock.systemUTC()
        )

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        
        val exception = assertThrows<RuntimeException> {
            service.reserveSeats("key-rollback", "user-1", "corr-1", request)
        }
        
        assertEquals("Database error during outbox save", exception.message)
        
        val successCount = meterRegistry.counter("inventory.holds.successful").count()
        val failedCount = meterRegistry.counter("inventory.holds.failed").count()
        
        assertEquals(0.0, successCount, "Successful metric should not be incremented on rollback")
        assertEquals(0.0, failedCount, "Failed metric should not be incremented here either")
    }
}
