package it.polito.cpo.client

import java.time.LocalDateTime

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

/** Result of the Inventory hold command. */
sealed interface HoldOutcome {

    data class Held(val reservationId: String, val expiresAt: LocalDateTime) : HoldOutcome

    /**
     * A definitive refusal before any payment exists, so the order fails with no compensation due
     * (doc:297).
     */
    data class Rejected(val code: InventoryRefusalCode, val detail: String?) : HoldOutcome
}
