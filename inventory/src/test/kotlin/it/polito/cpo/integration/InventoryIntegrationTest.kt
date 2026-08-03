package it.polito.cpo.integration

import it.polito.cpo.model.IdempotencyRecord
import it.polito.cpo.repository.IdempotencyRecordRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.time.ZoneOffset

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class InventoryIntegrationTest {

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
    lateinit var idempotencyRecordRepository: IdempotencyRecordRepository

    @MockitoBean
    lateinit var reactiveJwtDecoder: ReactiveJwtDecoder

    @BeforeEach
    fun setup() = runBlocking {
        idempotencyRecordRepository.deleteAll()
    }

    @Test
    fun `should execute real DELETE R2DBC query for expired idempotency records`() = runBlocking {
        // Arrange: Insert a record that is very old
        val oldDate = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2)
        val record = IdempotencyRecord(
            idempotencyKey = "key-123",
            principalId = "user-123",
            operation = "POST",
            requestFingerprint = "fingerprint",
            responseStatusCode = 200,
            responseBody = null,
            createdAt = oldDate,
            isNewRecord = true
        )
        val saved = idempotencyRecordRepository.save(record)
        assertNotNull(saved)

        // Verify it was saved
        var allRecords = idempotencyRecordRepository.findAll().toList()
        assertEquals(1, allRecords.size)

        // Act: Delete records created before 1 hour ago
        val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
        val deletedCount = idempotencyRecordRepository.deleteByCreatedAtBefore(cutoff)

        // Assert: It should be deleted
        assertEquals(1, deletedCount, "One record should be deleted")
        
        allRecords = idempotencyRecordRepository.findAll().toList()
        assertTrue(allRecords.isEmpty(), "Table should be empty after delete")
    }

    @Autowired
    lateinit var reservationRepository: it.polito.cpo.repository.ReservationRepository

    @Test
    fun `should execute UPDATE RETURNING native query and map results correctly`() = runBlocking {
        // Arrange: one expired reservation, one valid
        reservationRepository.deleteAll()
        
        val expired = it.polito.cpo.model.Reservation(
            reservationId = "res-expired",
            orderId = java.util.UUID.randomUUID(),
            routeId = "route-1",
            status = "HELD",
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
            isNewRecord = true
        )
        val valid = it.polito.cpo.model.Reservation(
            reservationId = "res-valid",
            orderId = java.util.UUID.randomUUID(),
            routeId = "route-1",
            status = "HELD",
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5),
            isNewRecord = true
        )
        
        reservationRepository.saveAll(listOf(expired, valid)).toList()

        // Act: expire and return
        val expiredReservations = reservationRepository.expireAndReturn().toList()

        // Assert: only the expired one was modified and returned
        assertEquals(1, expiredReservations.size, "Only one reservation should have expired")
        assertEquals("res-expired", expiredReservations.first().reservationId)
        assertEquals("EXPIRED", expiredReservations.first().status, "Returned object should have the new status")
        
        // Verify DB state
        val expiredInDb = reservationRepository.findById("res-expired")
        assertEquals("EXPIRED", expiredInDb?.status)
        
        val validInDb = reservationRepository.findById("res-valid")
        assertEquals("HELD", validInDb?.status)
    }
}
