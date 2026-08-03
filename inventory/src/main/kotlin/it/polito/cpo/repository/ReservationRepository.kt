package it.polito.cpo.repository

import it.polito.cpo.model.Reservation
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
interface ReservationRepository : CoroutineCrudRepository<Reservation, String> {

    /**
     * Finds a reservation by its identifier.
     */
    suspend fun findByReservationId(reservationId: String): Reservation?

    /**
     * Finds a reservation by its associated order identifier.
     */
    suspend fun findByOrderId(orderId: UUID): Reservation?

    /**
     * Updates a batch of expired held reservations to expired and returns them.
     */
    @Query(
        """
        WITH expired_batch AS (
            SELECT reservation_id FROM reservations 
            WHERE status = 'HELD' AND expires_at < CURRENT_TIMESTAMP 
            ORDER BY expires_at 
            FOR UPDATE SKIP LOCKED 
            LIMIT 100
        )
        UPDATE reservations 
        SET status = 'EXPIRED' 
        FROM expired_batch 
        WHERE reservations.reservation_id = expired_batch.reservation_id 
        RETURNING reservations.*
    """
    )
    fun expireAndReturn(): Flow<Reservation>

    /**
     * Retrieves the current database timestamp.
     */
    @Query("SELECT CURRENT_TIMESTAMP")
    suspend fun getCurrentTimestamp(): OffsetDateTime
    
    /**
     * Updates a reservation status to cancelled if currently held.
     */
    @Modifying
    @Query("UPDATE reservations SET status = 'CANCELLED' WHERE reservation_id = :reservationId AND status = 'HELD'")
    suspend fun cancelIfHeld(reservationId: String): Int

    /**
     * Finds a reservation by identifier acquiring a row-level update lock.
     */
    @Query("SELECT * FROM reservations WHERE reservation_id = :reservationId FOR UPDATE")
    suspend fun findByReservationIdForUpdate(reservationId: String): Reservation?
}
