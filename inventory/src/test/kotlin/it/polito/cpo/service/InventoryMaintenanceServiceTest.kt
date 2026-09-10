package it.polito.cpo.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.r2dbc.postgresql.codec.Json
import it.polito.cpo.fakes.FakeIdempotencyRecordRepository
import it.polito.cpo.fakes.FakeReservationRepository
import it.polito.cpo.fakes.FakeSeatRepository
import it.polito.cpo.model.IdempotencyRecord
import it.polito.cpo.model.Reservation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.reactive.TransactionCallback
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class InventoryMaintenanceServiceTest {

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

    @Test
    fun `sweepExpiredReservations uses provided clock to determine expired reservations and releases seats`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        
        val fixedTime = OffsetDateTime.parse("2026-07-30T12:00:00Z")
        resRepo.currentDbTime = fixedTime
        
        // Reservation that expires in the past (11:55) -> SHOULD be expired
        resRepo.save(Reservation(
            reservationId = "res-past",
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = "HELD",
            expiresAt = OffsetDateTime.parse("2026-07-30T11:55:00Z")
        ))
        
        // Reservation that expires in the future (12:05) -> SHOULD NOT be expired
        resRepo.save(Reservation(
            reservationId = "res-future",
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = "HELD",
            expiresAt = OffsetDateTime.parse("2026-07-30T12:05:00Z")
        ))

        val meterRegistry = SimpleMeterRegistry()
        val maintenanceService = InventoryMaintenanceService(
            seatRepository = seatRepo,
            reservationRepository = resRepo,
            idempotencyRecordRepository = FakeIdempotencyRecordRepository(),
            transactionalOperator = fakeTransactionalOperator,
            meterRegistry = meterRegistry
        )

        val count = maintenanceService.sweepExpiredReservations()

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

        val maintenanceService = InventoryMaintenanceService(
            seatRepository = seatRepo,
            reservationRepository = resRepo,
            idempotencyRecordRepository = FakeIdempotencyRecordRepository(),
            transactionalOperator = failingTransactionalOperator,
            meterRegistry = meterRegistry
        )

        val count = maintenanceService.sweepExpiredReservations()

        assertEquals(0, count)
        assertEquals(1.0, meterRegistry.counter("inventory.holds.sweep.errors").count())
    }

    @Test
    fun `purgeExpiredIdempotency deletes records older than 30 minutes using DB clock`() = runTest {
        val resRepo = FakeReservationRepository()
        val fixedDbTime = OffsetDateTime.parse("2026-07-30T12:00:00Z")
        resRepo.currentDbTime = fixedDbTime

        val meterRegistry = SimpleMeterRegistry()
        val idemRepo = FakeIdempotencyRecordRepository()

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

        val maintenanceService = InventoryMaintenanceService(
            seatRepository = FakeSeatRepository(),
            reservationRepository = resRepo,
            idempotencyRecordRepository = idemRepo,
            transactionalOperator = fakeTransactionalOperator,
            meterRegistry = meterRegistry
        )
        val deleted = maintenanceService.purgeExpiredIdempotency()

        assertEquals(1, deleted)
        assertNull(idemRepo.findByKeyAndPrincipal("old-key", "user-1"))
        assertNotNull(idemRepo.findByKeyAndPrincipal("recent-key", "user-1"))
        assertEquals(1.0, meterRegistry.counter("inventory.idempotency.purged").count())
    }

    @Test
    fun `purgeExpiredIdempotency catches exception and records error metric on repository failure`() = runTest {
        val idemRepo = FakeIdempotencyRecordRepository(shouldThrow = true)
        val meterRegistry = SimpleMeterRegistry()

        val maintenanceService = InventoryMaintenanceService(
            seatRepository = FakeSeatRepository(),
            reservationRepository = FakeReservationRepository(),
            idempotencyRecordRepository = idemRepo,
            transactionalOperator = fakeTransactionalOperator,
            meterRegistry = meterRegistry
        )
        val deleted = maintenanceService.purgeExpiredIdempotency()

        assertEquals(0, deleted)
        assertEquals(1.0, meterRegistry.counter("inventory.idempotency.purge.errors").count())
        assertEquals(0.0, meterRegistry.counter("inventory.idempotency.purged").count())
    }
}
