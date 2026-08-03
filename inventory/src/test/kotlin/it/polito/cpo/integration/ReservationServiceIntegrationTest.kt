package it.polito.cpo.integration

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationStatus
import it.polito.cpo.model.Reservation
import it.polito.cpo.repository.IdempotencyRecordRepository
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReservationRepository
import it.polito.cpo.repository.SeatRepository
import it.polito.cpo.service.ReservationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.dao.DataAccessException
import it.polito.cpo.observability.ApiException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ReservationServiceIntegrationTest : BaseIntegrationTest() {

    @Autowired
    lateinit var reservationService: ReservationService

    @Autowired
    lateinit var seatRepository: SeatRepository

    @Autowired
    lateinit var reservationRepository: ReservationRepository

    @Autowired
    lateinit var idempotencyRecordRepository: IdempotencyRecordRepository

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    lateinit var databaseClient: DatabaseClient

    @BeforeEach
    fun setup() = runBlocking {
        databaseClient.sql("DELETE FROM seats").then().awaitSingleOrNull()
        reservationRepository.deleteAll()
        idempotencyRecordRepository.deleteAll()
        outboxEventRepository.deleteAll()
        meterRegistry.clear()
    }

    private fun insertSeat(routeId: String, seatId: String, status: String) {
        databaseClient.sql("INSERT INTO seats (route_id, seat_id, status) VALUES (:routeId, :seatId, :status)")
            .bind("routeId", routeId)
            .bind("seatId", seatId)
            .bind("status", status)
            .fetch().rowsUpdated().block()
    }

    @Test
    fun `should save Reservation, Idempotency and OutboxEvent atomically on successful hold`() = runBlocking {
        // Arrange
        val routeId = "route-1"
        val seatId = "seat-1"
        insertSeat(routeId, seatId, "AVAILABLE")
        
        val request = ReservationRequest(
            orderId = UUID.randomUUID(),
            routeId = routeId,
            seats = listOf(seatId)
        )

        // Act
        val response = reservationService.reserveSeats("key-1", "user-1", "corr-1", request)

        // Assert
        assertEquals(ReservationStatus.HELD, response.status)

        val reservations = reservationRepository.findAll().toList()
        assertEquals(1, reservations.size)
        assertEquals(response.reservationId, reservations.first().reservationId)

        val idempotencyRecords = idempotencyRecordRepository.findAll().toList()
        assertEquals(1, idempotencyRecords.size)

        val outboxEvents = outboxEventRepository.findAll().toList()
        assertEquals(1, outboxEvents.size)
        assertEquals("inventory-reserved", outboxEvents.first().type)
        
        // Assert Metrics
        assertEquals(1.0, meterRegistry.counter("inventory.holds.attempted").count())
        assertEquals(1.0, meterRegistry.counter("inventory.holds.successful").count())
    }

    private fun getSeatStatus(routeId: String, seatId: String): String? {
        val row = databaseClient.sql("SELECT status FROM seats WHERE route_id = :routeId AND seat_id = :seatId")
            .bind("routeId", routeId)
            .bind("seatId", seatId)
            .fetch().first().block()
        return row?.get("status") as String?
    }

    @Test
    fun `should rollback entirely if an exception occurs during transaction`() = runBlocking {
        // Arrange
        val routeId = "route-2"
        val seatId = "seat-2"
        insertSeat(routeId, seatId, "AVAILABLE")
        
        val request = ReservationRequest(
            orderId = UUID.randomUUID(),
            routeId = routeId,
            seats = listOf(seatId)
        )

        // Force an exception during transaction by using an excessively long idempotency key
        // which will violate the database column length constraint and throw DataIntegrityViolationException
        val tooLongKey = "k".repeat(500)

        // Act
        try {
            reservationService.reserveSeats(tooLongKey, "user-2", "corr-2", request)
            fail("Exception should have been thrown due to column length constraint")
        } catch (e: Exception) {
            // Assert
            // It might be DataIntegrityViolationException or a similar Spring exception
            assertTrue(e is DataAccessException, "Expected DataAccessException but was ${e.javaClass}")
        }

        // Verify Rollback
        val reservations = reservationRepository.findAll().toList()
        assertTrue(reservations.isEmpty(), "Reservation should be rolled back")

        val idempotencyRecords = idempotencyRecordRepository.findAll().toList()
        assertTrue(idempotencyRecords.isEmpty(), "IdempotencyRecord should be rolled back")

        // The seat should still be AVAILABLE because the hold was rolled back
        val seatStatus = getSeatStatus(routeId, seatId)
        assertEquals("AVAILABLE", seatStatus)
        
        // Assert Metrics (attempted but NOT successful)
        assertEquals(1.0, meterRegistry.counter("inventory.holds.attempted").count())
        // successful metric should not exist or be 0
        val successfulCounter = meterRegistry.find("inventory.holds.successful").counter()
        assertTrue(successfulCounter == null || successfulCounter.count() == 0.0)
    }

    @Test
    fun `should handle concurrent requests with the same idempotency key safely`() = runBlocking {
        // Arrange
        val routeId = "route-concurrent"
        val seatId = "seat-concurrent"
        insertSeat(routeId, seatId, "AVAILABLE")

        val request = ReservationRequest(
            orderId = UUID.randomUUID(),
            routeId = routeId,
            seats = listOf(seatId)
        )

        // Act
        // Fire multiple concurrent requests simulating a race condition
        val concurrencyCount = 5
        val responses = coroutineScope {
            val deferredResponses = (1..concurrencyCount).map {
                async(Dispatchers.IO) {
                    reservationService.reserveSeats("concurrent-key", "user-concurrent", "corr-$it", request)
                }
            }
            awaitAll(*deferredResponses.toTypedArray())
        }

        // Assert
        assertEquals(concurrencyCount, responses.size)
        // All responses should be successful (one actual, others cached)
        assertTrue(responses.all { it.status == ReservationStatus.HELD })
        
        // They should all return the EXACT same reservation ID
        val uniqueReservationIds = responses.map { it.reservationId }.toSet()
        assertEquals(1, uniqueReservationIds.size)

        // Verify Database state
        val reservations = reservationRepository.findAll().toList()
        assertEquals(1, reservations.size, "Only 1 reservation should be created")

        val idempotencyRecords = idempotencyRecordRepository.findAll().toList()
        assertEquals(1, idempotencyRecords.size, "Only 1 idempotency record should be created")

        val outboxEvents = outboxEventRepository.findAll().toList()
        assertEquals(1, outboxEvents.size, "Only 1 outbox event should be created")
        
        // Assert Metrics
        // Under high concurrency, the fast-path check misses, so ALL threads will increment "attempted"
        // But only 1 transaction will succeed, the rest will fallback and increment "hits".
        assertEquals(concurrencyCount.toDouble(), meterRegistry.counter("inventory.holds.attempted").count())
        assertEquals(1.0, meterRegistry.counter("inventory.holds.successful").count())
        assertEquals(4.0, meterRegistry.counter("inventory.idempotency.hits").count())
    }

    @Test
    fun `should persist EXPIRED status when confirmReservation detects logical expiration and throws 409`() = runBlocking {
        // Arrange
        val routeId = "route-exp"
        val seatId = "seat-exp"
        insertSeat(routeId, seatId, "AVAILABLE")
        seatRepository.holdSeats(routeId, listOf(seatId), "res-exp-123")
        
        val orderId = UUID.randomUUID()
        val expired = Reservation(
            reservationId = "res-exp-123",
            orderId = orderId,
            routeId = routeId,
            status = "HELD",
            expiresAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(5),
            isNewRecord = true
        )
        reservationRepository.save(expired)

        // Act
        try {
            reservationService.confirmReservation("res-exp-123", orderId)
            fail("Should throw ApiException")
        } catch (e: it.polito.cpo.observability.ApiException) {
            // Assert exception
            assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.status)
            assertEquals("RESERVATION_EXPIRED", e.code)
        }

        // Assert DB state is updated
        val updated = reservationRepository.findById("res-exp-123")
        assertEquals("EXPIRED", updated?.status)
        
        val seatStatus = getSeatStatus(routeId, seatId)
        assertEquals("AVAILABLE", seatStatus)
    }

    @Test
    fun `should handle concurrent confirmation and release safely`() = runBlocking {
        // Arrange
        val routeId = "route-race"
        val seatId = "seat-race"
        insertSeat(routeId, seatId, "AVAILABLE")
        val reservationId = "res-race-1"
        seatRepository.holdSeats(routeId, listOf(seatId), reservationId)
        val orderId = UUID.randomUUID()
        val held = Reservation(
            reservationId = reservationId,
            orderId = orderId,
            routeId = routeId,
            status = "HELD",
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            isNewRecord = true
        )
        reservationRepository.save(held)

        // Act - Simulate race condition
        coroutineScope {
            val deferredResponses = listOf(
                async(Dispatchers.IO) {
                    try {
                        reservationService.confirmReservation(reservationId, orderId)
                        "CONFIRMED"
                    } catch (_: Exception) {
                        "CONFIRM_FAILED"
                    }
                },
                async(Dispatchers.IO) {
                    try {
                        reservationService.releaseReservation(reservationId)
                        "RELEASED"
                    } catch (_: Exception) {
                        "RELEASE_FAILED"
                    }
                }
            )
            awaitAll(*deferredResponses.toTypedArray())
        }

        // Assert
        // State must be consistent: either CONFIRMED (and seats SOLD) or CANCELLED (and seats AVAILABLE)
        val finalRes = reservationRepository.findById(reservationId)
        val finalSeat = getSeatStatus(routeId, seatId)

        when (finalRes?.status) {
            "CONFIRMED" -> {
                assertEquals("SOLD", finalSeat)
            }
            "CANCELLED" -> {
                assertEquals("AVAILABLE", finalSeat)
            }
            else -> {
                fail("Invalid final state after race: ${finalRes?.status}")
            }
        }
    }

    @Test
    fun `sweepExpiredReservations should expire oldest HELD reservations first and release seats`() = runBlocking {
        // Arrange
        val routeId = "route-sweep"
        val seat1 = "seat-sweep-1"
        val seat2 = "seat-sweep-2"
        val seat3 = "seat-sweep-3"
        insertSeat(routeId, seat1, "AVAILABLE")
        insertSeat(routeId, seat2, "AVAILABLE")
        insertSeat(routeId, seat3, "AVAILABLE")

        val resOld = "res-old"
        val resNew = "res-new"
        val resFuture = "res-future"

        seatRepository.holdSeats(routeId, listOf(seat1), resOld)
        seatRepository.holdSeats(routeId, listOf(seat2), resNew)
        seatRepository.holdSeats(routeId, listOf(seat3), resFuture)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        // resOld: expired 10 minutes ago
        reservationRepository.save(
            Reservation(
                reservationId = resOld,
                orderId = UUID.randomUUID(),
                routeId = routeId,
                status = "HELD",
                expiresAt = now.minusMinutes(10),
                isNewRecord = true
            )
        )
        // resNew: expired 1 minute ago
        reservationRepository.save(
            Reservation(
                reservationId = resNew,
                orderId = UUID.randomUUID(),
                routeId = routeId,
                status = "HELD",
                expiresAt = now.minusMinutes(1),
                isNewRecord = true
            )
        )
        // resFuture: expires in 10 minutes
        reservationRepository.save(
            Reservation(
                reservationId = resFuture,
                orderId = UUID.randomUUID(),
                routeId = routeId,
                status = "HELD",
                expiresAt = now.plusMinutes(10),
                isNewRecord = true
            )
        )

        // Act
        val expiredCount = reservationService.sweepExpiredReservations()

        // Assert
        assertEquals(2, expiredCount)
        assertEquals("EXPIRED", reservationRepository.findById(resOld)?.status)
        assertEquals("EXPIRED", reservationRepository.findById(resNew)?.status)
        assertEquals("HELD", reservationRepository.findById(resFuture)?.status)

        // Seats for expired reservations should be released back to AVAILABLE
        assertEquals("AVAILABLE", getSeatStatus(routeId, seat1))
        assertEquals("AVAILABLE", getSeatStatus(routeId, seat2))
        // Seat for future reservation should remain HELD
        assertEquals("HELD", getSeatStatus(routeId, seat3))

        // Check metric
        assertEquals(2.0, meterRegistry.counter("inventory.holds.expired").count())
    }

    @Test
    fun `reserveSeats should fail and not leave orphan held seats when one requested seat is already unavailable`() = runBlocking {
        // Arrange
        val routeId = "route-atomic-fail"
        val seatAvail = "seat-avail-1"
        val seatSold = "seat-sold-1"
        insertSeat(routeId, seatAvail, "AVAILABLE")
        insertSeat(routeId, seatSold, "SOLD")

        val orderId = UUID.randomUUID()
        val idempotencyKey = "key-atomic-fail-${UUID.randomUUID()}"
        val request = ReservationRequest(
            orderId = orderId,
            routeId = routeId,
            seats = listOf(seatAvail, seatSold)
        )

        // Act
        val response = reservationService.reserveSeats(
            idempotencyKey = idempotencyKey,
            principalId = "user-atomic",
            correlationId = "corr-atomic-1",
            request = request
        )

        // Assert
        // 1. Response status should be FAILED
        assertEquals(ReservationStatus.FAILED, response.status)

        // 2. AVAILABLE seat must NOT have been converted to HELD (no partial/orphan hold leak)
        assertEquals("AVAILABLE", getSeatStatus(routeId, seatAvail))
        assertEquals("SOLD", getSeatStatus(routeId, seatSold))

        // 3. No reservation record created in DB
        val storedRes = reservationRepository.findById(response.reservationId)
        assertNull(storedRes)

        // 4. Idempotency record stored with 409 Conflict
        val record = idempotencyRecordRepository.findByKeyAndPrincipal(idempotencyKey, "user-atomic")
        assertNotNull(record)
        assertEquals(409, record?.responseStatusCode)
    }

    @Test
    fun `reserveSeats should successfully hold all seats when all requested seats are available`() = runBlocking {
        // Arrange
        val routeId = "route-atomic-ok"
        val seat1 = "seat-ok-1"
        val seat2 = "seat-ok-2"
        insertSeat(routeId, seat1, "AVAILABLE")
        insertSeat(routeId, seat2, "AVAILABLE")

        val orderId = UUID.randomUUID()
        val idempotencyKey = "key-atomic-ok-${UUID.randomUUID()}"
        val request = ReservationRequest(
            orderId = orderId,
            routeId = routeId,
            seats = listOf(seat1, seat2)
        )

        // Act
        val response = reservationService.reserveSeats(
            idempotencyKey = idempotencyKey,
            principalId = "user-atomic",
            correlationId = "corr-atomic-2",
            request = request
        )

        // Assert
        assertEquals(ReservationStatus.HELD, response.status)
        assertEquals("HELD", getSeatStatus(routeId, seat1))
        assertEquals("HELD", getSeatStatus(routeId, seat2))

        val storedRes = reservationRepository.findById(response.reservationId)
        assertNotNull(storedRes)
        assertEquals("HELD", storedRes?.status)
        assertEquals(orderId, storedRes?.orderId)
    }

    @Test
    fun `reserveSeats should return 409 when orderId already exists in DB even after idempotency record is purged`() = runBlocking {
        // Arrange
        val routeId = "route-dup-order"
        val seat1 = "seat-dup-1"
        val seat2 = "seat-dup-2"
        insertSeat(routeId, seat1, "AVAILABLE")
        insertSeat(routeId, seat2, "AVAILABLE")

        val orderId = UUID.randomUUID()
        val idempotencyKey1 = "key-first-${UUID.randomUUID()}"
        val request = ReservationRequest(
            orderId = orderId,
            routeId = routeId,
            seats = listOf(seat1)
        )

        // First reservation succeeds
        val response1 = reservationService.reserveSeats(
            idempotencyKey = idempotencyKey1,
            principalId = "user-test",
            correlationId = "corr-1",
            request = request
        )
        assertEquals(ReservationStatus.HELD, response1.status)

        // Simulate idempotency record purge
        idempotencyRecordRepository.deleteAll()

        // Second reservation with same orderId, different idempotencyKey, and available seat2
        val request2 = ReservationRequest(
            orderId = orderId,
            routeId = routeId,
            seats = listOf(seat2)
        )
        val idempotencyKey2 = "key-second-${UUID.randomUUID()}"
        val ex = assertThrows<ApiException> {
            reservationService.reserveSeats(
                idempotencyKey = idempotencyKey2,
                principalId = "user-test",
                correlationId = "corr-2",
                request = request2
            )
        }

        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("ORDER_ALREADY_RESERVED", ex.code)
    }

    @Test
    fun `confirmReservation should rollback partial seat changes and reservation status on RESERVATION_SEAT_CONFLICT`() = runBlocking {
        // Arrange
        val routeId = "route-seat-conflict"
        val seat1 = "seat-conflict-1"
        val seat2 = "seat-conflict-2"
        val reservationId = "res-conflict-rollback"
        val orderId = UUID.randomUUID()

        insertSeat(routeId, seat1, "HELD")
        insertSeat(routeId, seat2, "AVAILABLE") // Inconsistent DB state: assigned reservation_id below, but status is AVAILABLE

        databaseClient.sql("UPDATE seats SET reservation_id = :resId WHERE route_id = :routeId AND seat_id IN (:s1, :s2)")
            .bind("resId", reservationId)
            .bind("routeId", routeId)
            .bind("s1", seat1)
            .bind("s2", seat2)
            .fetch().rowsUpdated().block()

        val reservation = Reservation(
            reservationId = reservationId,
            orderId = orderId,
            routeId = routeId,
            status = "HELD",
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            isNewRecord = true
        )
        reservationRepository.save(reservation)

        // Act & Assert
        val ex = assertThrows<ApiException> {
            reservationService.confirmReservation(reservationId, orderId)
        }

        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("RESERVATION_SEAT_CONFLICT", ex.code)

        // Verify transaction rolled back: seat1 remains HELD (NOT committed to SOLD), reservation remains HELD (NOT CONFIRMED)
        val res = reservationRepository.findById(reservationId)
        assertEquals("HELD", res?.status)

        val seat1Status = getSeatStatus(routeId, seat1)
        assertEquals("HELD", seat1Status)
    }
}
