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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.OffsetDateTime
import java.time.ZoneOffset

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
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
    fun `Phase 1 - should execute real DELETE R2DBC query for expired idempotency records`() = runBlocking {
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
}
