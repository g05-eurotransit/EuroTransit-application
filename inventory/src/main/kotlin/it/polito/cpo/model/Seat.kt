package it.polito.cpo.model

import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate

/**
 * Represents a physical seat on a route for a specific travel date.
 * The Primary Key is composite (route_id, travel_date, seat_id) in the database.
 */
@Table("seats")
data class Seat(
    val routeId: String,
    val travelDate: LocalDate,
    val seatId: String,
    val status: String,
    val reservationId: String? = null
)
