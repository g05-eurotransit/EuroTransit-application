package it.polito.cpo.payments.service.payment

/**
 * A checkout compensation versus a customer-initiated commercial refund. Both emit
 * `payment-refunded`, so this is what tells them apart. Derived from the idempotency-key namespace
 * that already separates them — `refund-{orderId}` vs `pay-refund-{orderId}` (ADR-015).
 */
enum class RefundReason {
    COMPENSATION,
    COMMERCIAL,
    UNKNOWN;

    companion object {
        fun fromIdempotencyKey(idempotencyKey: String): RefundReason = when {
            idempotencyKey.startsWith("pay-refund-") -> COMMERCIAL
            idempotencyKey.startsWith("refund-") -> COMPENSATION
            else -> UNKNOWN
        }
    }
}
