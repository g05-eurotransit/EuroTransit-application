package it.polito.cpo.service

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scheduled trigger for the expiration sweep of stale seat holds. All business and
 * transactional logic stays in [ReservationService].
 *
 * Disabled unless `inventory.reservations.expiration.enabled` is explicitly `true`.
 * Until Orders confirms reservations synchronously after payment authorization, a paid
 * order can still sit on a `HELD` reservation, and this sweep would release its seats
 * back to `AVAILABLE` and allow them to be sold twice. See the implementation gate in
 * docs/design/reservation-confirmation.md.
 */
@Component
@ConditionalOnProperty(
    name = ["inventory.reservations.expiration.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class ReservationExpirationJob(
    private val reservationService: ReservationService,
) {
    private val log = LoggerFactory.getLogger(ReservationExpirationJob::class.java)

    /**
     * Triggers the periodic sweep of expired reservations.
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
}
