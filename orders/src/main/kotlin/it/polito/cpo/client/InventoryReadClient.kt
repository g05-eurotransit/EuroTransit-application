package it.polito.cpo.client

import java.time.LocalDate

/**
 * Orders' view of the Inventory seat-map read.
 *
 * Separate from [InventoryClient] on purpose, and not just for tidiness: that interface is the
 * money-path command surface, and every implementation of it binds the `inventory` circuit breaker,
 * retry and bulkhead. A read method added beside those would sooner or later be composed with the
 * money-path chain — the operators are applied per call, so nothing but attention prevents it. Two
 * interfaces make the mistake impossible rather than discouraged.
 *
 * The result is **advisory**. A seat reported available can still lose the hold race, so
 * `SEATS_UNAVAILABLE` at checkout remains the authoritative answer; this only exists so a customer
 * is not told about it for the first time after paying.
 */
interface InventoryReadClient {

    /**
     * Seats and their availability for one route on one travel date.
     *
     * Both parameters are required because seats are keyed by `(route_id, travel_date, seat_id)`
     * since #64: the same physical seat is sold separately for each day, so a route alone does not
     * identify a bookable thing.
     *
     * Unknown outcomes — timeout, open circuit, 5xx — are thrown rather than returned, exactly as in
     * [InventoryClient]. Here the reason is weaker than invariant 8 (no money is at stake) but the
     * shape is the same: the caller must not be able to mistake "Inventory did not answer" for "no
     * seats are free", which is what an empty list on failure would say.
     */
    suspend fun getSeatAvailability(routeId: String, travelDate: LocalDate): SeatAvailabilityOutcome
}

/** Result of the Inventory seat-map read. */
sealed interface SeatAvailabilityOutcome {

    /**
     * Inventory answered. [seats] may legitimately be empty for a known route with nothing free.
     */
    data class Found(val seats: List<SeatAvailability>) : SeatAvailabilityOutcome

    /**
     * Inventory has no seats at all for this route and date.
     *
     * Today Inventory answers `404 ROUTE_NOT_FOUND` both for a route it does not know and for one
     * that is entirely sold out, which are different answers for a customer — an error versus a
     * valid "nothing free". Orders cannot tell them apart from here, so it reports the conservative
     * one. Raised with Inventory on #68; when that is split, this variant narrows to the genuine
     * unknown-route case.
     */
    data object NotFound : SeatAvailabilityOutcome
}

/**
 * One seat, with availability collapsed to a single flag.
 *
 * `HELD` and `SOLD` are deliberately indistinguishable, and no reservation id, order id or principal
 * crosses this boundary: telling one customer that a seat is *held* rather than *sold* reports
 * another customer's in-flight checkout.
 */
data class SeatAvailability(
    val seatId: String,
    val available: Boolean,
)
