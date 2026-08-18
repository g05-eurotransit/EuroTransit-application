package it.polito.cpo.model

/**
 * Durable checkout phases. The stored value is what selects the resume action when Kafka
 * redelivers `order-placed`, so every state must be meaningful on its own — see the state
 * table in docs/design/reservation-confirmation.md:138-144.
 *
 * ACCEPTED -> RESERVING -> PAYMENT_PENDING -> CONFIRMING_INVENTORY -> CONFIRMED
 * CONFIRMING_INVENTORY -> COMPENSATING -> FAILED
 */
enum class OrderStatus {
    ACCEPTED,
    RESERVING,
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
    ;

    /** Terminal phases acknowledge a redelivered event without making any remote call (doc:144). */
    val isTerminal: Boolean
        get() = this == CONFIRMED || this == FAILED || this == CANCELLED
}
