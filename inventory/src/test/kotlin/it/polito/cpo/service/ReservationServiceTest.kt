package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.r2dbc.postgresql.codec.Json
import it.polito.cpo.contracts.events.InventoryFailedEvent
import it.polito.cpo.contracts.events.InventoryReservedEvent
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationResponse
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
import org.springframework.dao.DuplicateKeyException
import it.polito.cpo.observability.ApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.reactive.TransactionCallback
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
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
        var batchReleaseCalledWith: Collection<String>? = null
        override suspend fun holdSeats(routeId: String, seatIds: List<String>, reservationId: String, expectedCount: Int): Int {
            return if (seatsAvailable) seatIds.size else 0
        }
        override suspend fun releaseSeats(reservationId: String): Int {
            releaseCalledWith = reservationId
            return 1
        }
        override suspend fun releaseSeatsForReservations(reservationIds: Collection<String>): Int {
            batchReleaseCalledWith = reservationIds
            return reservationIds.size
        }
        var confirmCalledWith: String? = null
        var confirmSeatsReturnCount: Int = 2
        var countByReservationIdReturnCount: Long = 2L
        override suspend fun confirmSeats(reservationId: String): Int {
            confirmCalledWith = reservationId
            return confirmSeatsReturnCount
        }
        override suspend fun countByReservationId(reservationId: String): Long {
            return countByReservationIdReturnCount
        }
    }

    private class FakeReservationRepository(
        private val delegate: ReservationRepository = Mockito.mock(ReservationRepository::class.java)
    ) : ReservationRepository by delegate {
        val store = HashMap<String, Reservation>()
        override suspend fun findByReservationId(reservationId: String): Reservation? {
            return store[reservationId]
        }
        override suspend fun findByReservationIdForUpdate(reservationId: String): Reservation? {
            return store[reservationId]
        }
        override suspend fun findByOrderId(orderId: UUID): Reservation? {
            return store.values.firstOrNull { it.orderId == orderId }
        }
        override suspend fun <S : Reservation> save(entity: S): S {
            val existing = store.values.firstOrNull { it.orderId == entity.orderId && it.reservationId != entity.reservationId }
            if (existing != null) {
                throw DuplicateKeyException("Unique index violation on uk_reservations_order_id")
            }
            store[entity.reservationId] = entity
            return entity
        }
        var currentDbTime: OffsetDateTime = OffsetDateTime.now()
        
        override fun expireAndReturn(): Flow<Reservation> {
            val expired = store.values.filter { it.status == "HELD" && it.expiresAt.isBefore(currentDbTime) }
            val updated = expired.map { it.copy(status = "EXPIRED") }
            updated.forEach { store[it.reservationId] = it }
            return flowOf(*updated.toTypedArray())
        }
        
        override suspend fun getCurrentTimestamp(): OffsetDateTime {
            return currentDbTime
        }
        
        override suspend fun cancelIfHeld(reservationId: String): Int {
            val res = store[reservationId]
            if (res != null && res.status == "HELD") {
                store[reservationId] = res.copy(status = "CANCELLED")
                return 1
            }
            return 0
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
        override suspend fun deleteByCreatedAtBefore(cutoff: OffsetDateTime): Int {
            val toRemove = store.filter { it.value.createdAt?.isBefore(cutoff) == true }.keys
            toRemove.forEach { store.remove(it) }
            return toRemove.size
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
        outboxRepo: FakeOutboxEventRepository,
        meterRegistry: MeterRegistry = SimpleMeterRegistry()
    ) = ReservationService(
        seatRepository = seatRepo,
        reservationRepository = resRepo,
        idempotencyRecordRepository = idemRepo,
        objectMapper = mapper,
        outboxEventRepository = outboxRepo,
        transactionalOperator = fakeTransactionalOperator,
        meterRegistry = meterRegistry
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
            responseBody = Json.of(mapper.writeValueAsString(cachedResponse))
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
        val cachedResponse = ReservationResponse(
            reservationId,
            ReservationStatus.HELD,
            LocalDateTime.now().plusMinutes(10)
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
            status = "EXPIRED",
            expiresAt = OffsetDateTime.now().minusMinutes(5)
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
        val cachedResponse = ReservationResponse(
            reservationId, 
            ReservationStatus.HELD, 
            LocalDateTime.now().plusMinutes(10)
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
            status = "CANCELLED",
            expiresAt = OffsetDateTime.now().minusMinutes(5)
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
        val meterRegistry = SimpleMeterRegistry()
        val service = buildService(
            FakeSeatRepository(),
            FakeReservationRepository(),
            idemRepo,
            FakeOutboxEventRepository(),
            meterRegistry
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
            responseBody = Json.of("{}")
        ))

        val exception = assertThrows<ApiException> {
            service.reserveSeats(idempotencyKey, principalId, correlationId, request)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("IDEMPOTENCY_CONFLICT", exception.code)
        assertEquals(0.0, meterRegistry.counter("inventory.idempotency.hits").count())
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
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        service.releaseReservation(reservationId)

        assertEquals(reservationId, seatRepo.releaseCalledWith)
        assertEquals("CANCELLED", resRepo.store[reservationId]?.status)
    }

    @Test
    fun `sweepExpiredReservations uses provided clock to determine expired reservations and releases seats`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        
        val fixedTime = OffsetDateTime.parse("2026-07-30T12:00:00Z")
        resRepo.currentDbTime = fixedTime
        
        val service = buildService(
            seatRepo, resRepo, FakeIdempotencyRecordRepository(), FakeOutboxEventRepository()
        )

        // Reservation that expires in the past (11:55) -> SHOULD be expired
        resRepo.save(Reservation(
            reservationId = "res-past",
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            status = "HELD",
            expiresAt = OffsetDateTime.parse("2026-07-30T11:55:00Z")
        ))
        
        // Reservation that expires in the future (12:05) -> SHOULD NOT be expired
        resRepo.save(Reservation(
            reservationId = "res-future",
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            status = "HELD",
            expiresAt = OffsetDateTime.parse("2026-07-30T12:05:00Z")
        ))

        val count = service.sweepExpiredReservations()

        assertEquals(1, count, "Only one reservation should have expired")
        assertEquals(listOf("res-past"), seatRepo.batchReleaseCalledWith?.toList(), "Seats for res-past should be released in batch")
        assertEquals("EXPIRED", resRepo.store["res-past"]?.status, "Past reservation should be marked EXPIRED")
        assertEquals("HELD", resRepo.store["res-future"]?.status, "Future reservation should remain HELD")
    }

    @Test
    fun `sweepExpiredReservations increments error counter on exception`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val meterRegistry = SimpleMeterRegistry()
        
        val failingTransactionalOperator = object : TransactionalOperator {
            override fun <T : Any> execute(action: TransactionCallback<T>): Flux<T> {
                return Flux.error(RuntimeException("DB connection failure during sweep"))
            }
            override fun <T : Any> transactional(flux: Flux<T>): Flux<T> = flux
            override fun <T : Any> transactional(mono: Mono<T>): Mono<T> = mono
        }

        val service = ReservationService(
            seatRepository = seatRepo,
            reservationRepository = resRepo,
            idempotencyRecordRepository = FakeIdempotencyRecordRepository(),
            objectMapper = mapper,
            outboxEventRepository = FakeOutboxEventRepository(),
            transactionalOperator = failingTransactionalOperator,
            meterRegistry = meterRegistry
        )

        val count = service.sweepExpiredReservations()

        assertEquals(0, count)
        assertEquals(1.0, meterRegistry.counter("inventory.holds.sweep.errors").count())
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
        val meterRegistry = SimpleMeterRegistry()
        val service = ReservationService(
            seatRepo, resRepo, idemRepo, failingOutboxRepo,
            mapper, fakeTransactionalOperator, meterRegistry
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
    @Test
    fun `confirmReservation successfully confirms a valid held reservation and updates seats`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val service = buildService(seatRepo, resRepo, FakeIdempotencyRecordRepository(), FakeOutboxEventRepository())

        val orderId = UUID.randomUUID()
        val reservationId = "res-confirm-1"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = orderId,
            routeId = "route-1",
            status = ReservationStatus.HELD.name,
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        val response = service.confirmReservation(reservationId, orderId)

        assertEquals(ReservationStatus.CONFIRMED, response.status)
        assertEquals("CONFIRMED", resRepo.store[reservationId]?.status)
        assertEquals(reservationId, seatRepo.confirmCalledWith)
    }

    @Test
    fun `confirmReservation throws 409 if orderId mismatches`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val service = buildService(seatRepo, resRepo, FakeIdempotencyRecordRepository(), FakeOutboxEventRepository())

        val reservationId = "res-confirm-2"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = UUID.randomUUID(), // Different order ID
            routeId = "route-1",
            status = ReservationStatus.HELD.name,
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        val exception = assertThrows<ApiException> {
            service.confirmReservation(reservationId, UUID.randomUUID()) // Requested order ID
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_ORDER_CONFLICT", exception.code)
    }

    @Test
    fun `confirmReservation throws 409 if real-time clock says it is expired, sets it to EXPIRED and releases seats`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        
        val fixedTime = OffsetDateTime.parse("2026-07-30T12:00:00Z")
        resRepo.currentDbTime = fixedTime
        
        val service = buildService(
            seatRepo, resRepo, FakeIdempotencyRecordRepository(), FakeOutboxEventRepository()
        )

        val orderId = UUID.randomUUID()
        val reservationId = "res-confirm-expired"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = orderId,
            routeId = "route-1",
            status = ReservationStatus.HELD.name,
            // Expired 5 minutes ago!
            expiresAt = OffsetDateTime.parse("2026-07-30T11:55:00Z")
        ))

        val exception = assertThrows<ApiException> {
            service.confirmReservation(reservationId, orderId)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_EXPIRED", exception.code)
        
        // Ensure it transitioned to EXPIRED and seats released
        assertEquals("EXPIRED", resRepo.store[reservationId]?.status)
        assertEquals(reservationId, seatRepo.releaseCalledWith)
    }

    @Test
    fun `confirmReservation throws 409 RESERVATION_SEAT_CONFLICT when seat count mismatch occurs`() = runTest {
        val seatRepo = FakeSeatRepository().apply {
            countByReservationIdReturnCount = 2L
            confirmSeatsReturnCount = 1 // Only 1 updated instead of expected 2
        }
        val resRepo = FakeReservationRepository()
        val service = buildService(seatRepo, resRepo, FakeIdempotencyRecordRepository(), FakeOutboxEventRepository())

        val orderId = UUID.randomUUID()
        val reservationId = "res-seat-mismatch"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = orderId,
            routeId = "route-1",
            status = ReservationStatus.HELD.name,
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        val exception = assertThrows<ApiException> {
            service.confirmReservation(reservationId, orderId)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_SEAT_CONFLICT", exception.code)
    }

    @Test
    fun `purgeExpiredIdempotency deletes records older than 30 minutes using DB clock`() = runTest {
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val fixedDbTime = OffsetDateTime.parse("2026-07-30T12:00:00Z")
        resRepo.currentDbTime = fixedDbTime

        val service = buildService(
            FakeSeatRepository(),
            resRepo,
            idemRepo,
            FakeOutboxEventRepository()
        )

        // Record created 35 minutes ago -> should be purged
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = "old-key",
            principalId = "user-1",
            operation = "RESERVE",
            requestFingerprint = "hash1",
            responseStatusCode = 201,
            responseBody = Json.of("{}"),
            createdAt = fixedDbTime.minusMinutes(35)
        ))

        // Record created 10 minutes ago -> should be kept
        idemRepo.save(IdempotencyRecord(
            idempotencyKey = "recent-key",
            principalId = "user-1",
            operation = "RESERVE",
            requestFingerprint = "hash2",
            responseStatusCode = 201,
            responseBody = Json.of("{}"),
            createdAt = fixedDbTime.minusMinutes(10)
        ))

        val deleted = service.purgeExpiredIdempotency()

        assertEquals(1, deleted)
        assertNull(idemRepo.findByKeyAndPrincipal("old-key", "user-1"))
        assertNotNull(idemRepo.findByKeyAndPrincipal("recent-key", "user-1"))
    }

    @Test
    fun `reserveSeats throws 409 ORDER_ALREADY_RESERVED when duplicate order_id occurs without idempotency record`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val idemRepo = FakeIdempotencyRecordRepository()
        val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, idemRepo, outboxRepo)

        val orderId = UUID.randomUUID()
        // Existing reservation in DB for this orderId (e.g. from an earlier hold whose idempotency key was purged)
        resRepo.save(Reservation(
            reservationId = "res-existing-123",
            orderId = orderId,
            routeId = "route-1",
            status = "CONFIRMED",
            expiresAt = OffsetDateTime.now()
        ))

        // New request arrives for the same orderId with a new idempotency key
        val request = ReservationRequest(
            orderId = orderId,
            routeId = "route-1",
            seats = listOf("seat-1", "seat-2")
        )

        val exception = assertThrows<ApiException> {
            service.reserveSeats(
                request = request,
                idempotencyKey = "new-key-after-purge",
                principalId = "user-1",
                correlationId = "corr-1"
            )
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("ORDER_ALREADY_RESERVED", exception.code)
    }

    @Test
    fun `generateFingerprint produces identical hash for reordered and duplicate seats`() {
        val service = buildService(FakeSeatRepository(), FakeReservationRepository(), FakeIdempotencyRecordRepository(), FakeOutboxEventRepository())
        val orderId = UUID.randomUUID()
        val req1 = ReservationRequest(orderId, listOf("1A", "1B", "1C"), "route-1")
        val req2 = ReservationRequest(orderId, listOf("1C", "1A", "1B"), "route-1")
        val req3 = ReservationRequest(orderId, listOf("1B", "1A", "1C", "1A", "1B"), "route-1")

        val method = service.javaClass.getDeclaredMethod("generateFingerprint", ReservationRequest::class.java).apply {
            isAccessible = true
        }

        val fp1 = method.invoke(service, req1) as String
        val fp2 = method.invoke(service, req2) as String
        val fp3 = method.invoke(service, req3) as String

        assertEquals(fp1, fp2)
        assertEquals(fp1, fp3)
    }
}
