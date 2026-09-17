package it.polito.cpo.client

/**
 * Result of a payment authorization.
 *
 * As with the Inventory outcomes, an unknown result has no variant here and can only be thrown. The
 * distinction matters most for `DEPENDENCY_FAILED`: the provider may well have authorized the
 * payment, so treating it as a decline would release the hold and fail an order whose money is
 * actually captured. Doc:300 requires keeping PAYMENT_PENDING and repeating `pay-{orderId}` instead.
 */
sealed interface AuthorizationOutcome {

    data class Authorized(val providerReference: String?) : AuthorizationOutcome

    /** Definitive: no money was taken. Release the hold and fail the order (doc:299). */
    data class Declined(val errorCode: String?) : AuthorizationOutcome
}

/**
 * Result of a payment reversal. Payments maps `refund-{orderId}` to a provider void or refund
 * depending on capture state; Orders never chooses between them (doc:309-313).
 */
sealed interface ReversalOutcome {

    /**
     * Definitively reversed. Only now may checkout compensation become FAILED (doc:150-152) or a
     * post-sale refund (ADR-015) return the seat.
     *
     * [providerReference] is `PaymentRefundResponse.refundReference`, carried through so the
     * post-sale refund can persist it (V15's `refund_provider_reference`) for audit. Checkout
     * compensation ignores it — nothing on `Order` records a compensation's provider reference,
     * only a commercial refund's.
     */
    data class Reversed(val providerReference: String?) : ReversalOutcome

    /**
     * Definitive reversal failure. Deliberately a value and not an exception: rethrowing would burn
     * the three Kafka redeliveries and dead-letter the record on every redrive, while the outcome
     * will not change by retrying. The order stays COMPENSATING with a PAYMENT_REVERSAL_FAILURE
     * reconciliation record and an alert (doc:306).
     */
    data class Failed(val code: String, val detail: String?) : ReversalOutcome
}
