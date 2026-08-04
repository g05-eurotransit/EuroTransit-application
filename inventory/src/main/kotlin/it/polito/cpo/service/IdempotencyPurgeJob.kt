package it.polito.cpo.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scheduled trigger for purging stale idempotency records. All business and
 * transactional logic stays in [ReservationService].
 *
 * Enabled by default, and deliberately independent of the reservation-expiration
 * toggle: this purge only bounds table growth against the 30-minute Inventory
 * retention documented in docs/design/api-design.md, and has no bearing on seat
 * availability or on the expiration gate.
 */
@Component
@ConditionalOnProperty(
    name = ["inventory.idempotency.purge.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class IdempotencyPurgeJob(
    private val reservationService: ReservationService,
) {
    private val log = LoggerFactory.getLogger(IdempotencyPurgeJob::class.java)

    /**
     * Triggers the periodic purge of expired idempotency records.
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
