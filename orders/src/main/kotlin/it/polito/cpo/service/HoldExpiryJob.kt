package it.polito.cpo.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The T1 expiry job (ADR-016): closes customer-hold orders whose customer did not pay before the hold
 * deadline. A scheduled trigger only; the transition and the release are
 * [CheckoutOrchestrator.expireHolds].
 *
 * Safe on every replica at once with no leader election: each order is closed by a compare-and-set
 * that re-checks the deadline against the database clock, so two replicas picking the same order
 * produce one cancellation and one no-op. The Inventory expiration sweep frees the seat itself at
 * the same deadline regardless, so a late or failed run here delays only the order's status.
 */
@Component
class HoldExpiryJob(
    private val checkoutOrchestrator: CheckoutOrchestrator,
) {
    private val log = LoggerFactory.getLogger(HoldExpiryJob::class.java)

    @Scheduled(
        initialDelayString = "\${orders.holds.expiry-initial-delay-ms:15000}",
        fixedDelayString = "\${orders.holds.expiry-interval-ms:15000}",
    )
    fun expireHolds() = runBlocking {
        try {
            val closed = checkoutOrchestrator.expireHolds()
            if (closed > 0) {
                log.info("Closed {} customer holds past their payment deadline", closed)
            }
        } catch (e: Exception) {
            // The next run retries; the candidates are re-read from the database every time.
            log.error("The hold expiry run failed", e)
        }
    }
}
