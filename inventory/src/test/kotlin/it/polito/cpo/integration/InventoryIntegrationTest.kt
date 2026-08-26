package it.polito.cpo.integration

import it.polito.cpo.model.IdempotencyRecord
import it.polito.cpo.repository.IdempotencyRecordRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class InventoryIntegrationTest : BaseIntegrationTest() {

    @Autowired
    lateinit var idempotencyRecordRepository: IdempotencyRecordRepository

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
            travelDate = LocalDate.now(),
            status = "HELD",
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC
        ).minusMinutes(5),
            isNewRecord = true
        )
        val valid = it.polito.cpo.model.Reservation(
            reservationId = "res-valid",
            orderId = java.util.UUID.randomUUID(),
            routeId = "route-1",
            travelDate = LocalDate.now(),
            status = "HELD",
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC
        ).plusMinutes(5),
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
