package it.polito.cpo.model

import org.springframework.data.relational.core.mapping.Table

/**
 * Represents a physical seat on a route.
 * The Primary Key is composite (route_id, seat_id) in the database.
 */
@Table("seats")
data class Seat(
    val routeId: String,
    val seatId: String,
    val status: String,
    val reservationId: String? = null
)
