package it.polito.cpo.controller.dtos

import it.polito.cpo.model.OrderStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

data class CheckoutRequest(
    val routeId: String,
    val seats: List<String>,
    /** The day being travelled. A route plus a seat is not a bookable thing on its own (issue #64). */
    val travelDate: LocalDate,
    /** What the client displayed. Checked against the offer's price when [offerId] is present. */
    val totalAmount: BigDecimal,
    val paymentMethodToken: String,
    /**
     * Which offer is being bought. Needed by the post-sale refund (ADR-015), whose policy and cutoff
     * are properties of the offer that was sold.
     *
     * **When present, it is resolved against Catalog and it decides the price.** The offer must
     * exist, must be sold on [routeId], and its price must equal [totalAmount] — otherwise the
     * checkout is rejected. [totalAmount] is therefore a consistency check on what the client
     * displayed, not the amount that gets authorized; the offer's own price and currency are.
     *
     * Optional for now: the SPA does not send it yet (issue #84), and requiring it here would reject
     * every checkout until that lands. While it is absent, [totalAmount] is still authorized
     * unvalidated, which is the part of issue #93 that only the SPA can close.
     */
    val offerId: String? = null,
)

data class CheckoutResponse(
    val orderId: UUID,
    val status: OrderStatus
)

data class OrderView(
    val orderId: UUID,
    val userId: String,
    val status: OrderStatus,
    val routeId: String,
    val seats: List<String>,
    /** Distinct from [createdAt]: the day of travel, not the day of purchase. */
    val travelDate: LocalDate,
    val totalAmount: BigDecimal,
    /**
     * The currency of [totalAmount]. Resolved from the offer, so an amount is never shown without
     * the unit it was actually authorized in.
     */
    val currency: String,
    val createdAt: LocalDateTime,
    /**
     * When the Inventory seat hold expires, as an instant. Non-null once seats are held.
     *
     * This is the deadline for the *checkout* to converge — it covers crash, retry and Kafka lag —
     * and NOT a payment window: the payment token arrives with the checkout request and the whole
     * pipeline runs server-side with no user interaction. A client must not present it as "you have
     * ten minutes to pay".
     */
    val reservationExpiresAt: OffsetDateTime? = null,
    /**
     * Why the checkout failed, from the closed `CustomerFailureCode` set. Non-null only on `FAILED`.
     *
     * Deliberately not the internal failure class: everything except "the seats were taken" and
     * "the payment was declined" collapses into `CHECKOUT_FAILED`, so an invariant violation or a
     * provider code can never reach a customer through this field.
     */
    val failureCode: String? = null,
    /** Ready-to-display text for [failureCode], derived from it rather than stored. */
    val failureMessage: String? = null,
    /**
     * The offer this order was bought against, as recorded at checkout. Null for orders placed
     * before the field existed, and for clients that do not send it yet.
     *
     * The SPA needs it to show what was bought and to decide whether to offer a refund at all: an
     * order with no offer has no refund terms and the refund endpoint will refuse it (ADR-015).
     */
    val offerId: String? = null,
    /**
     * The refund terms this order was sold under, frozen at checkout, so the SPA can decide whether
     * to show a refund button at all and until when.
     *
     * All three are null together for an order placed without an offer, which has no refund terms.
     * [refundCutoffMinutesBeforeDeparture] is null when [refundable] is false — there is no cutoff on
     * a ticket that cannot be refunded — and null when it is refundable right up to departure.
     *
     * [departureAt] is the instant [refundCutoffMinutesBeforeDeparture] counts back from, already
     * composed from the travel date and the departure station's zone, so a client never has to do
     * that arithmetic. Eligibility is still decided server-side against the Orders database clock
     * (ADR-015): these fields let the SPA render the right thing, not make the decision.
     *
     * `termsVersion` is deliberately **not** exposed. It is an audit marker for which revision of the
     * rule was accepted, and a client that had it might use it as a key into a catalogue that no
     * longer holds that revision.
     */
    val refundable: Boolean? = null,
    val refundCutoffMinutesBeforeDeparture: Int? = null,
    val departureAt: OffsetDateTime? = null,
)

/**
 * Result of an operator redrive. [orderStatus] is the durable phase the resumed pipeline will pick up
 * from, which is the only thing that decides what happens next.
 */
data class RedriveResponse(
    val orderId: UUID,
    val orderStatus: OrderStatus,
    val attemptCount: Int,
)

/** [evidenceReference] is the incident or audit reference; resolving without one is rejected. */
data class ResolveReconciliationRequest(
    val evidenceReference: String,
)

data class ResolveReconciliationResponse(
    val orderId: UUID,
    val recordId: UUID,
    val kind: String,
    val status: String,
    val resolvedAt: OffsetDateTime,
)

/**
 * The seat map for one route on one travel date, as Orders publishes it.
 *
 * Deliberately a type owned by Orders rather than Inventory's `SeatAvailabilityResponse` from
 * `money-path-contracts`. Reusing the shared type would make the public response *be* the internal
 * contract, so an Inventory change would silently become a public API change — the same reason
 * `CustomerFailureCode` exists instead of exposing `FailureClassifier`'s vocabulary.
 *
 * The answer is **advisory**: a seat reported free can still lose the hold race, and
 * `SEATS_UNAVAILABLE` at checkout stays the authoritative outcome.
 */
data class SeatAvailabilityView(
    val routeId: String,
    val travelDate: LocalDate,
    val seats: List<SeatView>,
)

/**
 * One seat. `available` collapses Inventory's `HELD` and `SOLD`, and no reservation id, order id or
 * principal is carried: distinguishing held from sold would report another customer's in-flight
 * checkout to whoever is looking at the picker.
 */
data class SeatView(
    val seatId: String,
    val available: Boolean,
)
