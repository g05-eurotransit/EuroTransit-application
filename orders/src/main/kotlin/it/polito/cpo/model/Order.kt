package it.polito.cpo.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

@Table("orders")
class Order(
    @Id private val id: UUID,
    val userId: String,
    var status: OrderStatus,
    val routeId: String,
    val seats: String, // comma-separated seats
    // The day being travelled, which together with routeId and seats identifies what was bought.
    // Without it a seat is sold once and for all rather than for one run (issue #64).
    val travelDate: LocalDate,
    val totalAmount: BigDecimal,
    // The currency of [totalAmount]. Required and deliberately not defaulted: a default here is what
    // would quietly restore the bug the column exists to remove — the authorization used to carry a
    // hardcoded "EUR" beside an amount that came from somewhere else entirely.
    //
    // Resolved from the offer when the checkout carried one. A checkout without an offer writes
    // "EUR" explicitly at the call site, so the assumption is recorded rather than implied.
    val currency: String,
    val createdAt: LocalDateTime,
    // Sandbox payment reference kept only until the order reaches a terminal state, so the
    // asynchronous pipeline can reconstruct the payment request. Cleared on CONFIRMED/FAILED.
    var paymentMethodToken: String? = null,
    // Delivery address for the confirmation/failure notification, captured from the JWT `email`
    // claim at checkout (the async pipeline has no JWT in scope). Not an ownership key.
    val recipientEmail: String? = null,
    // Inventory reservation id, set once seats are held in the async pipeline. Enables a
    // pre-payment cancellation to release the held seats. Null before RESERVING completes.
    var reservationId: String? = null,
    // When that hold expires, as decided by the Inventory database clock. Stored alongside the
    // reservation id in the same atomic transition so the two can never disagree, and exposed to the
    // customer as the checkout countdown. Null before RESERVING completes.
    var reservationExpiresAt: OffsetDateTime? = null,
    // Why the checkout entered COMPENSATING, so a redelivery that resumes after a crash can pick
    // the right reconciliation record kind and know whether releasing the hold is safe at all.
    // Null outside COMPENSATING.
    var compensationReason: String? = null,
    // The customer-visible reason the checkout failed, from the closed CustomerFailureCode set.
    // Distinct from compensationReason, which is the internal operator vocabulary: this one is
    // returned by the public API, so it never carries an invariant violation or a provider code.
    // Set only on the terminal FAILED transition; null in every other state.
    var failureCode: String? = null,
    // Which offer was bought. Resolved against Catalog at checkout: it exists, it belongs to
    // routeId, and its price is the one in totalAmount — so unlike in V13, this is a validated value
    // and the fields below were read from it.
    //
    // Still nullable until the SPA sends it (issue #84). While it is optional, an order with no
    // offer is an order whose amount was never checked against anything, which is the part of issue
    // #93 that stays open; it also has no refund terms, and the refund endpoint refuses it.
    val offerId: String? = null,
    // The refund rule the customer accepted, resolved from the offer at checkout and frozen here.
    //
    // Stored as the rule and not merely as [offerTermsVersion] because Catalog keeps no history: it
    // resolves offers from a flat list of current ones, so a version marker alone would point at
    // terms that may since have been edited. ADR-015's goal, by the means that reaches it.
    //
    // Null together, for an order placed without an offer: it has no refund terms and the refund
    // endpoint refuses it.
    val refundable: Boolean? = null,
    val refundCutoffMinutesBeforeDeparture: Int? = null,
    // Audit marker for which revision of the rule above was accepted. Not a substitute for it.
    val offerTermsVersion: Int? = null,
    // When this run departs, composed once at checkout as travelDate + the offer's departure time in
    // the departure station's zone. The refund compares this one stored instant against the database
    // clock: no zone arithmetic on the refund path, and no dependency on Catalog being reachable then.
    val departureAt: OffsetDateTime? = null,
) : Persistable<UUID> {

    @Transient
    private var isNew: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNew

    fun setAsNew(isNew: Boolean): Order {
        this.isNew = isNew
        return this
    }
}
