package it.polito.cpo.repository

import it.polito.cpo.model.Reservation
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface ReservationRepository : CoroutineCrudRepository<Reservation, String> {
    suspend fun findByReservationId(reservationId: String): Reservation?
    
    // Atomically expires reservations and returns the affected rows
    @Query("UPDATE reservations SET status = 'EXPIRED' WHERE status = 'HELD' AND expires_at < CURRENT_TIMESTAMP RETURNING *")
    fun expireAndReturn(): Flow<Reservation>

    @Query("SELECT CURRENT_TIMESTAMP")
    suspend fun getCurrentTimestamp(): OffsetDateTime

    @Query("SELECT * FROM reservations WHERE reservation_id = :reservationId FOR UPDATE")
    suspend fun findByReservationIdForUpdate(reservationId: String): Reservation?
}
