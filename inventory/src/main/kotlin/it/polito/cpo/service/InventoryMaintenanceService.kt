package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.repository.IdempotencyRecordRepository
import it.polito.cpo.repository.ReservationRepository
import it.polito.cpo.repository.SeatRepository
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

@Service
class InventoryMaintenanceService(
    private val reservationRepository: ReservationRepository,
    private val seatRepository: SeatRepository,
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val transactionalOperator: TransactionalOperator,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(InventoryMaintenanceService::class.java)

    /**
     * Marks expired held reservations as expired and releases their seats.
     */
    suspend fun sweepExpiredReservations(): Int {
        var count = 0
        try {
            transactionalOperator.executeAndAwait {
                val expiredReservations = reservationRepository.expireAndReturn().toList()
                if (expiredReservations.isNotEmpty()) {
                    val reservationIds = expiredReservations.map { it.reservationId }
                    seatRepository.releaseSeatsForReservations(reservationIds)
                    count = expiredReservations.size
                    logger.info("Swept and expired {} reservations, seats released in batch: {}", count, reservationIds)
                }
            }
            if (count > 0) {
                meterRegistry.counter("inventory.holds.expired").increment(count.toDouble())
            }
        } catch (e: Exception) {
            logger.error("Failed during atomic sweepExpiredReservations", e)
            meterRegistry.counter("inventory.holds.sweep.errors").increment()
        }
        return count
    }

    /**
     * Deletes idempotency records created before the specified retention cutoff.
     */
    suspend fun purgeExpiredIdempotency(retentionMinutes: Long = 30): Int {
        var count = 0
        try {
            val cutoff = reservationRepository.getCurrentTimestamp().minusMinutes(retentionMinutes)
            count = idempotencyRecordRepository.deleteByCreatedAtBefore(cutoff)
            if (count > 0) {
                meterRegistry.counter("inventory.idempotency.purged").increment(count.toDouble())
            }
        } catch (e: Exception) {
            logger.error("Failed during purgeExpiredIdempotency", e)
            meterRegistry.counter("inventory.idempotency.purge.errors").increment()
        }
        return count
    }
}
