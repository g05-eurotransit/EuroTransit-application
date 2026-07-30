package it.polito.cpo.repository

import it.polito.cpo.model.Reservation
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ReservationRepository : CoroutineCrudRepository<Reservation, String> {
    suspend fun findByReservationId(reservationId: String): Reservation?
    
    // Atomically expires reservations and returns the affected rows
    @org.springframework.data.r2dbc.repository.Query("UPDATE reservations SET status = 'EXPIRED' WHERE status = 'HELD' AND expires_at < :now RETURNING *")
    fun expireAndReturn(now: java.time.OffsetDateTime): kotlinx.coroutines.flow.Flow<Reservation>
}
