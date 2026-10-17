package it.polito.cpo.model

/**
 * Durable checkout phases. The stored value is what selects the resume action when Kafka
 * redelivers `order-placed`, so every state must be meaningful on its own — see the state
 * table in docs/design/reservation-confirmation.md:138-144.
 *
 * ACCEPTED -> RESERVING -> PAYMENT_PENDING -> CONFIRMING_INVENTORY -> CONFIRMED
 * CONFIRMING_INVENTORY -> COMPENSATING -> FAILED
 * CONFIRMED -> REFUNDING -> REFUNDED
 * RESERVING -> HELD_AWAITING_PAYMENT -> PAYMENT_PENDING   (customer-hold flow, ADR-016)
 *
 * The last line is a separate durable process (ADR-015), not a continuation of the checkout
 * pipeline above it: it has its own trigger, its own consumer (`RefundOrchestrator`), and reaches
 * `CONFIRMED` as its only entry point.
 */
enum class OrderStatus {
    ACCEPTED,
    RESERVING,

    /**
     * Customer-hold flow only (ADR-016): the seats are held, the reservation id and the T1 deadline
     * are stored, and the customer has not paid yet. Nothing in the pipeline moves an order out of
     * here: only the payment command, a customer cancellation, or the T1 expiry job, each a
     * compare-and-set on this predecessor so exactly one wins. A redelivered `order-placed` is
     * acknowledged without a remote call, which is what keeps Kafka from ever starting a payment.
     *
     * Not terminal: the order is waiting, not settled.
     */
    HELD_AWAITING_PAYMENT,

    PAYMENT_PENDING,

    /**
     * Payment is authorized and the Inventory confirmation is required, or its result is
     * unknown. Resumed by repeating the same idempotent confirmation PUT; never compensated
     * on an unknown outcome (invariant 8, doc:92).
     */
    CONFIRMING_INVENTORY,

    /**
     * Checkout cannot complete after authorization, so the payment reversal is required or its
     * result is unknown. Non-terminal on purpose: unresolved money must not be reported as a
     * finished order (doc:150-152).
     */
    COMPENSATING,

    CONFIRMED,
    FAILED,
    CANCELLED,

    /**
     * A post-sale refund was accepted and committed; the payment reversal, the seat return, or both
     * may still be outstanding. This is the resume point for `RefundOrchestrator`'s own consumer
     * (ADR-015), exactly as `COMPENSATING` is for the checkout pipeline: deliberately **not**
     * terminal from money's point of view, because a refund whose reversal or seat return is stuck
     * must not be reported as finished.
     *
     * It nonetheless answers `true` from [isTerminal] below, which looks like a contradiction and is
     * not one: [isTerminal] answers a narrower question than "is this order fully settled" — it is
     * "may the *checkout* pipeline acknowledge a redelivered `order-placed` without a remote call?" —
     * and the answer for an order already being refunded is yes regardless of whether the refund
     * itself has converged. The refund's own consumer decides whether to resume by comparing against
     * this state directly, never through [isTerminal] (see `RefundOrchestrator.processRefundRequested`).
     */
    REFUNDING,

    /** Money reversed, seat returned. The only state a refund can end in — there is no REFUND_FAILED. */
    REFUNDED,
    ;

    /**
     * Whether the *checkout* pipeline may acknowledge a redelivered `order-placed` event without
     * making any remote call (doc:144).
     *
     * `REFUNDING` and `REFUNDED` both answer yes: from the checkout consumer's point of view a
     * refunded (or refunding) order is exactly as settled as a confirmed one, and a stale
     * `order-placed` redelivery for either must never re-run the checkout. That `REFUNDING` is
     * itself unresolved and resumable is true and irrelevant here — it is resumed by a different
     * consumer, on a different predicate, never this one (see the KDoc on `REFUNDING`).
     */
    val isTerminal: Boolean
        get() = this == CONFIRMED || this == FAILED || this == CANCELLED || this == REFUNDING || this == REFUNDED
}
