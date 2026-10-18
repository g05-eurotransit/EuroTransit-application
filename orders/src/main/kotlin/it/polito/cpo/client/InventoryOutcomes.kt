package it.polito.cpo.client

import java.time.OffsetDateTime

/**
 * The complete set of Inventory business refusals Orders is contractually prepared to act on, as
 * mapped in docs/design/reservation-confirmation.md:193-205.
 *
 * A code outside this set is not a refusal: the client rethrows it as an unknown outcome.
 */
enum class InventoryRefusalCode {
    RESERVATION_NOT_FOUND,
    RESERVATION_ORDER_CONFLICT,
    RESERVATION_EXPIRED,
    RESERVATION_CANCELLED,
    RESERVATION_SEAT_CONFLICT,

    /** Hold endpoint only: the requested seats are not available. Wire shape is `{status: FAILED}`. */
    SEATS_UNAVAILABLE,

    /**
     * Hold endpoint only: a reservation already exists for this order but its idempotency record has
     * been purged, so Inventory cannot replay the original response. Because the Inventory
     * idempotency retention (30 minutes) outlives the hold deadline (10 minutes), the existing
     * reservation is necessarily terminal by the time this can happen.
     */
    ORDER_ALREADY_RESERVED,

    /** Hold endpoint only: the key was reused with a different payload. */
    IDEMPOTENCY_CONFLICT,

    /**
     * Refund endpoint only: the reservation exists but is not CONFIRMED (and not already REFUNDED,
     * which is not a refusal at all — see [RefundReservationOutcome.Refunded]). Reachable only if
     * Orders and Inventory disagree about whether the sale ever completed, since Orders only ever
     * calls the refund endpoint for an order it has itself moved out of CONFIRMED.
     */
    RESERVATION_NOT_CONFIRMED,
    ;

    /**
     * Whether this refusal describes a state the model says cannot happen, and therefore needs an
     * operator rather than just a compensation (doc:198-201).
     *
     * Expiry and cancellation are ordinary races and are deliberately excluded: the hold simply lost
     * to the deadline or to the customer. Ownership conflicts, seat mismatches and a missing
     * reservation are different — Orders held a reservation id that Inventory now disagrees about.
     */
    val isInvariantViolation: Boolean
        get() = this == RESERVATION_ORDER_CONFLICT ||
            this == RESERVATION_SEAT_CONFLICT ||
            this == RESERVATION_NOT_FOUND

    /**
     * Whether compensation may release this reservation.
     *
     * On an ownership conflict the hold belongs to a DIFFERENT order, so releasing it would corrupt
     * a stranger's checkout; on a missing reservation there is nothing to release. Everything else is
     * either still held or an idempotent no-op server-side.
     */
    val isReleasable: Boolean
        get() = this != RESERVATION_ORDER_CONFLICT && this != RESERVATION_NOT_FOUND

    internal companion object {
        fun from(code: String): InventoryRefusalCode? = when (code) {
            // ReservationResponse body shape on the 409 for unavailable seats.
            "FAILED" -> SEATS_UNAVAILABLE
            else -> entries.firstOrNull { it.name == code }
        }
    }
}

/**
 * Result of the Inventory confirmation command.
 *
 * There is deliberately no variant for a timeout, a 5xx, or an open circuit: an unknown outcome can
 * only leave the client as an exception, so it propagates to the Kafka listener, the offset is not
 * committed, and it can never be pattern-matched into a compensation. That is invariant 8
 * (docs/design/reservation-confirmation.md:92) expressed as a type rather than as a convention.
 */
sealed interface ConfirmReservationOutcome {

    /**
     * Inventory has COMMITTED the reservation as CONFIRMED and its seats as SOLD. Returned both for
     * the first successful call and for every valid replay — Inventory never exposes a separate
     * "already confirmed" state (doc:185-187).
     */
    data class Confirmed(val reservationId: String) : ConfirmReservationOutcome

    /** A definitive refusal: the sale did not happen. Leads to COMPENSATING (doc:198-202). */
    data class Refused(val code: InventoryRefusalCode, val detail: String?) : ConfirmReservationOutcome
}

/**
 * Result of the Inventory payment-window command (ADR-016): the one-time T2 restart of a
 * customer-hold reservation's deadline, asked for before Payments is called.
 *
 * Only definitive answers are variants. `RESERVATION_NOT_HELD` (a sold reservation behind an unpaid
 * order) is deliberately not one: it is outside [InventoryRefusalCode], so the client rethrows it as
 * an unknown outcome and the order stays PAYMENT_PENDING for an operator rather than failing as if no
 * money could have moved.
 */
sealed interface PaymentWindowOutcome {

    /** The window is open — now, or by an earlier call this replays — and runs until [expiresAt]. */
    data class Opened(val reservationId: String, val expiresAt: OffsetDateTime) : PaymentWindowOutcome

    /** A definitive refusal. Payments has not been called for this order, so it fails unpaid. */
    data class Refused(val code: InventoryRefusalCode, val detail: String?) : PaymentWindowOutcome
}

/** Result of the Inventory hold command. */
sealed interface HoldOutcome {

    data class Held(val reservationId: String, val expiresAt: OffsetDateTime) : HoldOutcome

    /**
     * A definitive refusal before any payment exists, so the order fails with no compensation due
     * (doc:297).
     */
    data class Rejected(val code: InventoryRefusalCode, val detail: String?) : HoldOutcome
}

/**
 * Result of the Inventory post-sale refund command (ADR-014's `CONFIRMED -> REFUNDED` reservation
 * transition, called from `RefundOrchestrator` after the payment reversal has succeeded).
 *
 * As with [ConfirmReservationOutcome], an unknown outcome has no variant here and can only be thrown,
 * so it propagates to the refund consumer, the offset is not committed, and it cannot be
 * pattern-matched into "the seat return failed" (invariant 8's shape, applied to the refund).
 */
sealed interface RefundReservationOutcome {

    /**
     * Inventory has committed the reservation as REFUNDED. Returned both for the first successful
     * call and for a replay of an already-REFUNDED reservation — this endpoint is idempotent by
     * state and takes no `Idempotency-Key`, unlike reserve and release.
     */
    data class Refunded(val reservationId: String) : RefundReservationOutcome

    /**
     * A definitive refusal. By the time this is called the payment has already been reversed
     * (ADR-015's money-first ordering), so the customer already has their money: this leads to a
     * [it.polito.cpo.model.ReconciliationKind.SEAT_RETURN_FAILURE] record and the order stays
     * REFUNDING, never a terminal failure.
     */
    data class Refused(val code: InventoryRefusalCode, val detail: String?) : RefundReservationOutcome
}
