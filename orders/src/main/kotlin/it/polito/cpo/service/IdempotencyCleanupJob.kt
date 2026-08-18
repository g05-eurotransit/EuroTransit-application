package it.polito.cpo.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Enforces the 24h checkout idempotency retention (api-design.md): periodically purges
 * `idempotency_keys` older than the retention window so the table does not grow unbounded.
 */
@Component
class IdempotencyCleanupJob(
    private val orderService: OrderService,
) {
    private val log = LoggerFactory.getLogger(IdempotencyCleanupJob::class.java)

    @Scheduled(
        initialDelayString = "\${orders.idempotency.cleanup-initial-delay-ms:600000}",
        fixedDelayString = "\${orders.idempotency.cleanup-interval-ms:3600000}",
    )
    fun purgeExpired() = runBlocking {
        val cutoff = LocalDateTime.now().minusHours(24)
        val deleted = orderService.purgeExpiredIdempotency(cutoff)
        if (deleted > 0) {
            log.info("Purged {} idempotency records older than {}", deleted, cutoff)
        }
    }
}
