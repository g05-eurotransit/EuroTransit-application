package it.polito.cpo.contracts.catalog

import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId

/**
 * The refund terms of an offer, in a form a service can evaluate.
 *
 * Catalog owns the policy; Orders applies it. The display prose on the offer is rendered *from*
 * this, never parsed *into* it: a money decision must never depend on reading a sentence.
 *
 * [cutoffMinutesBeforeDeparture] is a duration and not a precomputed instant on purpose. Orders
 * composes the departure instant itself from `travelDate + departureTime` in the route's
 * [OfferDetail.departureZone] and compares it against its own database clock, for the same reason
 * hold expiry uses the database clock: JVM clocks across replicas disagree at the boundary. A
 * precomputed instant would move that decision into Catalog, which knows nothing about orders.
 *
 * Null when [refundable] is false — there is no cutoff on a ticket that cannot be refunded.
 */
data class RefundPolicy(
    val refundable: Boolean,
    val cutoffMinutesBeforeDeparture: Int?,
)

/**
 * One offer resolved by id, with the route departure fields it takes to evaluate its own cutoff.
 *
 * The departure fields are denormalized from the route deliberately: Orders resolves exactly one
 * offer per refund and must not need a second call to learn when the train leaves.
 *
 * [termsVersion] is the monotonic marker Orders persists alongside the offer id at checkout, so
 * editing an offer's refund conditions later cannot retroactively rewrite what a past customer
 * accepted. Bump it on any change to [refundPolicy].
 */
data class OfferDetail(
    val offerId: String,
    val routeId: String,
    val fareClass: String,
    val price: BigDecimal,
    val currency: String,
    /** Display text, rendered from [refundPolicy]. Never parse this. */
    val conditions: String,
    val refundPolicy: RefundPolicy,
    val termsVersion: Int,
    val departureTime: LocalTime,
    /** IANA zone id of the departure station, e.g. `Europe/Rome`. */
    val departureZone: ZoneId,
)
