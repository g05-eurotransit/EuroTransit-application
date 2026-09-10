package it.polito.cpo.catalog

import java.time.LocalTime

/**
 * The part of a Catalog route a notification needs, and nothing more.
 *
 * Deliberately not the Catalog `Route` type: Notifications reads a public API, and binding to the
 * producer's domain class would turn any Catalog field change into a compile break here. Unknown
 * fields on the wire are ignored.
 *
 * [departureTime] is the local time at the departure station, which is exactly what a traveller
 * wants to read. It is not converted to the reader's zone, so no zone is needed to display it.
 */
data class RouteSummary(
    val routeId: String,
    val origin: String,
    val destination: String,
    val departureTime: LocalTime,
)
