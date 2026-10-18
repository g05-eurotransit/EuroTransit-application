package it.polito.cpo.model

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

data class Route(
    val routeId: String,
    val origin: String,
    val destination: String,
    val departureTime: LocalTime,
    val arrivalTime: LocalTime,
    // IANA zone id of the departure station. Required and deliberately not defaulted: a default is
    // exactly what would silently reintroduce the bug it exists to prevent. `departureTime` is a
    // zone-less LocalTime and Orders holds `travelDate` as a LocalDate — a date plus a zone-less
    // time is not a moment, and a refund cutoff compared against a non-moment is not a rule. Every
    // seeded route shares the CET offset today, which masks this; it would surface as a wrong
    // refund decision on the first route outside that offset, and at each DST boundary.
    val departureZone: ZoneId,
    val trainType: String,
    val operatingDays: List<String>,
) {
    val travelTime: Duration
        get() = Duration.between(departureTime, arrivalTime)
            .let { if (it.isNegative) it.plusDays(1) else it }
}
