package it.polito.cpo.model

import java.time.Duration
import java.time.LocalTime

data class Route(
    val routeId: String,
    val origin: String,
    val destination: String,
    val departureTime: LocalTime,
    val arrivalTime: LocalTime,
    val trainType: String,
    val operatingDays: List<String>,
) {
    val travelTime: Duration
        get() = Duration.between(departureTime, arrivalTime)
            .let { if (it.isNegative) it.plusDays(1) else it }
}
