package it.polito.cpo.repository

import it.polito.cpo.model.Seat
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface SeatRepository : CoroutineCrudRepository<Seat, String> {

    /**
     * Updates available seats to held for a route, date and reservation when all seats are available.
     */
    @Modifying
    @Query("""
        WITH available_seats AS (
            SELECT seat_id 
            FROM seats 
            WHERE route_id = :routeId 
              AND travel_date = :travelDate
              AND seat_id IN (:seatIds) 
              AND status = 'AVAILABLE' 
            FOR UPDATE
        )
        UPDATE seats 
        SET status = 'HELD', reservation_id = :reservationId 
        WHERE route_id = :routeId 
          AND travel_date = :travelDate
          AND seat_id IN (SELECT seat_id FROM available_seats) 
          AND (SELECT COUNT(*) FROM available_seats) = :expectedCount
    """)
    suspend fun holdSeats(
        routeId: String,
        travelDate: LocalDate,
        seatIds: List<String>,
        reservationId: String,
        expectedCount: Int = seatIds.size
    ): Int

    /**
     * Updates held seats for a reservation back to available.
     */
    @Modifying
    @Query("UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL WHERE reservation_id = :reservationId AND status = 'HELD'")
    suspend fun releaseSeats(reservationId: String): Int

    /**
     * Updates held seats for multiple reservations back to available in batch.
     */
    @Modifying
    @Query("UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL WHERE reservation_id IN (:reservationIds) AND status = 'HELD'")
    suspend fun releaseSeatsForReservations(reservationIds: Collection<String>): Int
    
    /**
     * Updates held seats for a reservation to sold.
     */
    @Modifying
    @Query("UPDATE seats SET status = 'SOLD' WHERE reservation_id = :reservationId AND status = 'HELD'")
    suspend fun confirmSeats(reservationId: String): Int
    
    /**
     * Updates sold seats for a refunded reservation back to available.
     */
    @Modifying
    @Query("UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL WHERE reservation_id = :reservationId AND status = 'SOLD'")
    suspend fun refundSeats(reservationId: String): Int
    
    /**
     * Counts the number of seats assigned to a reservation.
     */
    @Query("SELECT COUNT(*) FROM seats WHERE reservation_id = :reservationId")
    suspend fun countByReservationId(reservationId: String): Long
}
