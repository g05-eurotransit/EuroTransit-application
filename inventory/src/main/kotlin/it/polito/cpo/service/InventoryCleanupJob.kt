package it.polito.cpo.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scheduled background job that acts as the trigger for all routine maintenance
 * tasks in the Inventory service. It delegates all business and transactional logic
 * to [ReservationService] to ensure clean architectural boundaries.
 */
@Component
@ConditionalOnProperty(
    name = ["inventory.cleanup.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class InventoryCleanupJob(
    private val reservationService: ReservationService,
) {
    private val log = LoggerFactory.getLogger(InventoryCleanupJob::class.java)

    /**
     * Periodically triggers the expiration of reservations that have exceeded their
     * hold duration (10 minutes). Ensuring seats are promptly released is critical
     * for preventing capacity leaks and lost sales.
     */
    @Scheduled(
        initialDelayString = "\${inventory.reservations.cleanup-initial-delay-ms:30000}",
        fixedDelayString = "\${inventory.reservations.cleanup-interval-ms:30000}",
    )
    fun sweepExpiredReservations() = runBlocking {
        val expired = reservationService.sweepExpiredReservations()
        if (expired > 0) {
            log.info("Swept {} expired reservations", expired)
        }
    }

    /**
     * Periodically cleans up stale idempotency keys to prevent unbounded database growth.
     * Per api-design.md, the Inventory service retains idempotency records for 30 minutes.
     */
    @Scheduled(
        initialDelayString = "\${inventory.idempotency.cleanup-initial-delay-ms:600000}",
        fixedDelayString = "\${inventory.idempotency.cleanup-interval-ms:600000}",
    )
    fun purgeExpiredIdempotency() = runBlocking {
        val deleted = reservationService.purgeExpiredIdempotency()
        if (deleted > 0) {
            log.info("Purged {} stale idempotency records", deleted)
        }
    }
}
