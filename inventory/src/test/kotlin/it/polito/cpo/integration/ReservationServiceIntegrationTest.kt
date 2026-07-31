package it.polito.cpo.integration

import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationStatus
import it.polito.cpo.repository.IdempotencyRecordRepository
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReservationRepository
import it.polito.cpo.repository.SeatRepository
import it.polito.cpo.service.ReservationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitAll

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ReservationServiceIntegrationTest {

    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("inventory")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}"
            }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var reservationService: ReservationService

    @Autowired
    lateinit var seatRepository: SeatRepository

    @Autowired
    lateinit var reservationRepository: ReservationRepository

    @Autowired
    lateinit var idempotencyRecordRepository: IdempotencyRecordRepository

    @Autowired
    lateinit var meterRegistry: io.micrometer.core.instrument.MeterRegistry

    @Autowired
    lateinit var outboxEventRepository: OutboxEventRepository

    @MockitoBean
    lateinit var reactiveJwtDecoder: ReactiveJwtDecoder

    @Autowired
    lateinit var databaseClient: org.springframework.r2dbc.core.DatabaseClient

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
            assertTrue(e is org.springframework.dao.DataAccessException, "Expected DataAccessException but was ${e.javaClass}")
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
        val responses = kotlinx.coroutines.coroutineScope {
            val deferredResponses = (1..concurrencyCount).map {
                async(kotlinx.coroutines.Dispatchers.IO) {
                    reservationService.reserveSeats("concurrent-key", "user-concurrent", "corr-$it", request)
                }
            }
            kotlinx.coroutines.awaitAll(*deferredResponses.toTypedArray())
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
}
