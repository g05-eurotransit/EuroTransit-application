package it.polito.cpo.client

import it.polito.cpo.contracts.catalog.RefundPolicy
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Orders' view of the Catalog offer lookup.
 *
 * Used in the **synchronous** checkout entry, never in the `order-placed` pipeline. A wrong price has
 * to come back as a `400` on the checkout call: the customer can correct a rejected request, and they
 * cannot correct a `FAILED` order they discover minutes later.
 *
 * Kept apart from the money-path clients for the same structural reason [InventoryReadClient] is:
 * the implementation binds its own `catalog-read` Resilience4j instances, so a browsing-driven
 * Catalog problem cannot open a breaker that hold, pay or confirm depend on.
 *
 * **Fail closed.** Unknown outcomes — timeout, open circuit, 5xx — are thrown rather than returned,
 * so the caller cannot mistake "Catalog did not answer" for "the price is fine". The trade is
 * deliberate and it is an availability cost: while Catalog is unreachable, checkouts carrying an
 * offer are rejected. The tempting alternative — trusting the client's amount when Catalog is down —
 * reopens issue #93 at exactly the moment nobody is watching.
 */
interface CatalogClient {

    /**
     * One offer by id, with everything Orders needs to price it and to freeze its refund terms.
     *
     * `GET /api/catalog/offers/{offerId}` denormalizes the route's departure fields onto the offer,
     * so resolving a checkout takes exactly one call and no second lookup on `/routes/{routeId}`.
     */
    suspend fun getOffer(offerId: String): OfferOutcome

    /**
     * One route's departure schedule, for the same-day departed-run filter on the seat map (#98).
     *
     * Separate from [getOffer] because the seat map has no offer: the customer is still choosing
     * seats and has not picked a fare. `GET /api/catalog/routes/{routeId}` is the only place the
     * departure fields exist for a route on its own.
     *
     * Called **only** for a `travelDate` of today. A future date cannot have departed, so browsing
     * traffic — which is nearly all of it — never reaches Catalog through this path at all. That is
     * what keeps a public read-heavy dependency off the common seat-map request.
     */
    suspend fun getRoute(routeId: String): RouteOutcome
}

/** Result of the Catalog route lookup. */
sealed interface RouteOutcome {

    data class Found(val route: RouteSchedule) : RouteOutcome

    /**
     * Catalog does not know this route id.
     *
     * A definitive answer, not a failure. The seat-map façade treats it as "no departure time to
     * compare against" and carries on to Inventory, which owns whether the route and date have seats
     * at all — so a route Catalog has never heard of keeps answering exactly as it did before this
     * filter existed, rather than gaining a new `404` from a second service.
     */
    data object NotFound : RouteOutcome
}

/**
 * One route's schedule as Orders needs it: when it leaves, and where that time is anchored.
 *
 * Mapped off the wire rather than sharing Catalog's `Route`, which lives in `catalog/` and is not a
 * published contract — only `OfferDetail` is. Three fields instead of the whole row, for the same
 * reason [ResolvedOffer] drops two: what cannot be reached cannot be misused. `origin`,
 * `destination` and `arrivalTime` are presentation, and the seat map does not render them.
 */
data class RouteSchedule(
    val routeId: String,
    val departureTime: LocalTime,
    /** IANA zone of the departure station; [departureTime] is zone-less without it. */
    val departureZone: ZoneId,
) {
    /** @see departureInstant */
    fun departureAt(travelDate: LocalDate): OffsetDateTime =
        departureInstant(travelDate, departureTime, departureZone)
}

/**
 * The instant a run departs, for a customer travelling on [travelDate].
 *
 * The one place this arithmetic happens, shared by [ResolvedOffer.departureAt] (the refund cutoff)
 * and [RouteSchedule.departureAt] (the departed-run filter). A date plus a zone-less local time is
 * not a moment, and both a cutoff and a "has it left yet" comparison need one.
 *
 * On the spring-forward DST gap the local time does not exist; `atZone` moves it forward by the
 * offset shift rather than failing, which is the right answer for a departure board.
 */
internal fun departureInstant(
    travelDate: LocalDate,
    departureTime: LocalTime,
    departureZone: ZoneId,
): OffsetDateTime = travelDate.atTime(departureTime).atZone(departureZone).toOffsetDateTime()

/** Result of the Catalog offer lookup. */
sealed interface OfferOutcome {

    data class Found(val offer: ResolvedOffer) : OfferOutcome

    /**
     * Catalog does not know this offer id.
     *
     * A definitive answer, not a failure: the client sent something that does not exist, which is a
     * `400` on the checkout rather than a retryable dependency problem.
     */
    data object NotFound : OfferOutcome
}

/**
 * One offer as Orders needs it, mapped off the wire type.
 *
 * Deliberately **not** `OfferDetail` from `money-path-contracts` passed through: the two fields that
 * are dropped are the point. `conditions` is display prose rendered *from* the policy — carrying it
 * here would put a parseable sentence within reach of a money decision, which the contract
 * explicitly forbids — and `fareClass` is presentation Orders has no use for. What cannot be reached
 * cannot be misused.
 */
data class ResolvedOffer(
    val offerId: String,
    /** The route this offer sells. Checkout rejects a request whose `routeId` disagrees. */
    val routeId: String,
    val price: BigDecimal,
    val currency: String,
    /** The machine-readable refund rule, frozen onto the order at checkout. */
    val refundPolicy: RefundPolicy,
    /** Audit marker for which revision of [refundPolicy] the customer accepted. */
    val termsVersion: Int,
    val departureTime: LocalTime,
    /** IANA zone of the departure station; [departureTime] is zone-less without it. */
    val departureZone: ZoneId,
) {

    /**
     * The instant this run departs, for a customer travelling on [travelDate].
     *
     * Delegates to [departureInstant], which is the one place this arithmetic happens. The refund
     * cutoff is a duration before departure, so it can only be evaluated against a moment — and a
     * date plus a zone-less local time is not one.
     * Computed once at checkout and persisted, so the refund path compares a stored instant against
     * the database clock with no zone arithmetic and no dependency on Catalog being reachable.
     *
     */
    fun departureAt(travelDate: LocalDate): OffsetDateTime =
        departureInstant(travelDate, departureTime, departureZone)
}
