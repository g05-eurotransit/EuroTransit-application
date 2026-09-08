package it.polito.cpo.service

import it.polito.cpo.fakes.FakeIdempotencyRecordRepository
import it.polito.cpo.fakes.FakeOutboxEventRepository
import it.polito.cpo.fakes.FakeReservationRepository
import it.polito.cpo.fakes.FakeSeatRepository
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
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.IdempotencyRecordRepository
import it.polito.cpo.repository.ReservationRepository
import it.polito.cpo.repository.SeatRepository
import it.polito.cpo.service.IdempotencyService
import it.polito.cpo.service.InventoryMaintenanceService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.dao.DuplicateKeyException
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
import java.time.LocalDate
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


    private fun buildService(
        seatRepo: FakeSeatRepository,
        resRepo: FakeReservationRepository,
        outboxRepo: FakeOutboxEventRepository,
        meterRegistry: MeterRegistry = SimpleMeterRegistry(),
        idempotencyService: IdempotencyService = Mockito.mock(IdempotencyService::class.java)
    ): ReservationService {
        return ReservationService(
            seatRepository = seatRepo,
            reservationRepository = resRepo,
            idempotencyService = idempotencyService,
            outboxEventRepository = outboxRepo,
            objectMapper = mapper,
            transactionalOperator = fakeTransactionalOperator,
            meterRegistry = meterRegistry
        )
    }

    @Test
    fun `reserveSeats successfully holds seats, persists state, and publishes Reserved event`() = runTest {
        val seatRepo = FakeSeatRepository(seatsAvailable = true)
        val resRepo = FakeReservationRepository()
                val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, outboxRepo)

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1", LocalDate.now())
        val principalId = "user-1"
        val correlationId = "corr-1"
        val idempotencyKey = "key-success"
        
        val response = service.reserveSeats(idempotencyKey, principalId, correlationId, request)

        assertEquals(
            ReservationStatus.HELD,
            response.status,
            "The reservation status should be HELD"
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
                val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, outboxRepo)

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1", LocalDate.now())
        val principalId = "user-1"
        val correlationId = "corr-1"
        val idempotencyKey = "key-oversell"
        
        val response = service.reserveSeats(idempotencyKey, principalId, correlationId, request)

        assertEquals(
            ReservationStatus.FAILED, 
            response.status, 
            "The reservation status should be FAILED due to oversell"
        )
        
        assertEquals(1, outboxRepo.store.size, "Exactly one OutboxEvent should be emitted")
        
        val emittedEvent = mapper.readValue(outboxRepo.store.first().payload.asString(), InventoryFailedEvent::class.java)
        assertEquals(correlationId, emittedEvent.correlationId)
        assertEquals(request.orderId, emittedEvent.orderId)
        assertEquals(principalId, emittedEvent.principalId)
    }

    @Test
    fun `releaseReservation successfully releases seats if reservation is held`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val service = buildService(
            seatRepo,
            resRepo,
            FakeOutboxEventRepository()
        )

        val reservationId = "res-cancel-1"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = UUID.randomUUID(),
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = ReservationStatus.HELD.name,
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        service.releaseReservation(reservationId)

        assertEquals(reservationId, seatRepo.releaseCalledWith)
        assertEquals("CANCELLED", resRepo.store[reservationId]?.status)
    }


    @Test
    fun `releaseReservation ignores request if reservation not found or already cancelled`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val service = buildService(
            seatRepo,
            resRepo,
            FakeOutboxEventRepository()
        )

        service.releaseReservation("non-existent")

    }

    @Test
    fun `reserveSeats throws exception during outbox save, rolls back and does not increment metrics`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
                
        val failingOutboxRepo = FakeOutboxEventRepository(shouldThrow = true)
        val meterRegistry = SimpleMeterRegistry()
        val service = buildService(
            seatRepo, resRepo, failingOutboxRepo, meterRegistry
        )

        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1", LocalDate.now())
        
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
        val meterRegistry = SimpleMeterRegistry()
        val service = buildService(seatRepo, resRepo, FakeOutboxEventRepository(), meterRegistry)

        val orderId = UUID.randomUUID()
        val reservationId = "res-confirm-1"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = orderId,
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = ReservationStatus.HELD.name,
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        val response = service.confirmReservation(reservationId, orderId)

        assertEquals(ReservationStatus.CONFIRMED, response.status)
        assertEquals("CONFIRMED", resRepo.store[reservationId]?.status)
        assertEquals(reservationId, seatRepo.confirmCalledWith)
        assertEquals(1.0, meterRegistry.counter("inventory.holds.confirmed").count())
    }

    @Test
    fun `confirmReservation throws 409 if orderId mismatches`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val meterRegistry = SimpleMeterRegistry()
        val service = buildService(seatRepo, resRepo, FakeOutboxEventRepository(), meterRegistry)

        val reservationId = "res-confirm-2"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = UUID.randomUUID(), // Different order ID
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = ReservationStatus.HELD.name,
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        val exception = assertThrows<ApiException> {
            service.confirmReservation(reservationId, UUID.randomUUID()) // Requested order ID
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_ORDER_CONFLICT", exception.code)
        assertEquals(0.0, meterRegistry.counter("inventory.holds.confirmed").count())
    }

    @Test
    fun `confirmReservation throws 409 if real-time clock says it is expired, sets it to EXPIRED and releases seats`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
        val meterRegistry = SimpleMeterRegistry()
        
        val fixedTime = OffsetDateTime.parse("2026-07-30T12:00:00Z")
        resRepo.currentDbTime = fixedTime
        
        val service = buildService(
            seatRepo, resRepo, FakeOutboxEventRepository(), meterRegistry
        )

        val orderId = UUID.randomUUID()
        val reservationId = "res-confirm-expired"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = orderId,
            routeId = "route-1",
            travelDate = LocalDate.now(),
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
        assertEquals(1.0, meterRegistry.counter("inventory.holds.expired").count())
        assertEquals(0.0, meterRegistry.counter("inventory.holds.confirmed").count())
    }

    @Test
    fun `confirmReservation throws 409 RESERVATION_SEAT_CONFLICT when seat count mismatch occurs`() = runTest {
        val seatRepo = FakeSeatRepository().apply {
            countByReservationIdReturnCount = 2L
            confirmSeatsReturnCount = 1 // Only 1 updated instead of expected 2
        }
        val resRepo = FakeReservationRepository()
        val meterRegistry = SimpleMeterRegistry()
        val service = buildService(seatRepo, resRepo, FakeOutboxEventRepository(), meterRegistry)

        val orderId = UUID.randomUUID()
        val reservationId = "res-seat-mismatch"
        resRepo.save(Reservation(
            reservationId = reservationId,
            orderId = orderId,
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = ReservationStatus.HELD.name,
            expiresAt = OffsetDateTime.now().plusMinutes(10)
        ))

        val exception = assertThrows<ApiException> {
            service.confirmReservation(reservationId, orderId)
        }

        assertEquals(HttpStatus.CONFLICT, exception.status)
        assertEquals("RESERVATION_SEAT_CONFLICT", exception.code)
        assertEquals(0.0, meterRegistry.counter("inventory.holds.confirmed").count())
    }


    @Test
    fun `reserveSeats throws 409 ORDER_ALREADY_RESERVED when duplicate order_id occurs without idempotency record`() = runTest {
        val seatRepo = FakeSeatRepository()
        val resRepo = FakeReservationRepository()
                val outboxRepo = FakeOutboxEventRepository()
        val service = buildService(seatRepo, resRepo, outboxRepo)

        val orderId = UUID.randomUUID()
        // Existing reservation in DB for this orderId (e.g. from an earlier hold whose idempotency key was purged)
        resRepo.save(Reservation(
            reservationId = "res-existing-123",
            orderId = orderId,
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = "CONFIRMED",
            expiresAt = OffsetDateTime.now()
        ))

        // New request arrives for the same orderId with a new idempotency key
        val request = ReservationRequest(
            orderId = orderId,
            routeId = "route-1",
            seats = listOf("seat-1", "seat-2"),
            travelDate = LocalDate.now()
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
        assertNotNull(exception.details)
        assertEquals("res-existing-123", exception.details!!["reservationId"])
        assertEquals("CONFIRMED", exception.details!!["status"])
    }

}
