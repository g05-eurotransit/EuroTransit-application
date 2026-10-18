package it.polito.cpo.service

/**
 * The complete set of checkout failure reasons a customer is allowed to see.
 *
 * Orders classifies failures internally with [FailureClassifier], whose labels are an operator
 * vocabulary: they name invariant violations, provider refusals and dependency faults, and they are
 * carried on metrics tags and `reconciliation_records.failure_class`. None of that belongs in a
 * public API response.
 *
 * This enum is the narrow public projection of that vocabulary. Only two internal labels map to a
 * distinct customer code, because only two describe something the customer can act on: pick
 * different seats, or pay with something else. **Everything else collapses to [CHECKOUT_FAILED] by
 * default**, which is the load-bearing property — the map is an allowlist, so a new internal
 * failure class added later cannot leak by being forgotten here. `CustomerFailureCodeTest` asserts
 * exactly that.
 */
enum class CustomerFailureCode(val customerMessage: String) {

    /** The hold was refused before any payment existed: someone else took the seats first. */
    SEATS_UNAVAILABLE(
        "The selected seats are no longer available. No payment was taken.",
    ),

    /** Payments definitively refused the authorization, so no money moved. */
    PAYMENT_DECLINED(
        "The payment was declined. No payment was taken.",
    ),

    /**
     * Everything else.
     *
     * The message stays neutral about money on purpose: this code covers both failures before any
     * authorization and checkouts reversed after one, so it cannot claim either that no payment was
     * taken or that one was refunded.
     */
    CHECKOUT_FAILED(
        "We could not complete your order.",
    ),
    ;

    companion object {

        /**
         * The allowlist. Keys are [FailureClassifier] labels; the inventory ones are the wire codes
         * lowercased by [FailureClassifier.forInventoryRefusal].
         */
        private val PUBLIC: Map<String, CustomerFailureCode> = mapOf(
            "seats_unavailable" to SEATS_UNAVAILABLE,
            FailureClassifier.PAYMENT_DECLINED to PAYMENT_DECLINED,
        )

        /**
         * Projects an internal failure class onto the public set, defaulting to [CHECKOUT_FAILED].
         *
         * Case-insensitive because the two producers disagree: the unpaid path passes the lowercase
         * [FailureClassifier] label, while the compensation path passes
         * `Order.compensationReason`, which stores the uppercase `InventoryRefusalCode` name.
         */
        fun from(internalFailureClass: String?): CustomerFailureCode =
            PUBLIC[internalFailureClass?.lowercase()] ?: CHECKOUT_FAILED

        /**
         * Reads back the stored column. Null stays null — a non-FAILED order simply has no reason.
         * An unrecognised value degrades to [CHECKOUT_FAILED] rather than throwing: a row written by
         * an older or newer deployment must not make `GET /api/orders/{id}` fail.
         */
        fun parse(stored: String?): CustomerFailureCode? = stored?.let { value ->
            entries.firstOrNull { it.name == value } ?: CHECKOUT_FAILED
        }
    }
}
