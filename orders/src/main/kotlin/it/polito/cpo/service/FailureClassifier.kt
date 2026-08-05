package it.polito.cpo.service

import it.polito.cpo.client.InventoryRefusalCode

/**
 * The closed set of low-cardinality failure labels used across the orchestrator, the reversal path and
 * the dead-letter consumer.
 *
 * These values are both a Micrometer tag and a database column (`reconciliation_records.failure_class`),
 * so the set must stay bounded: an unbounded tag blows up the metric cardinality, and free text in the
 * column makes the operator queries useless. The specific provider or wire code belongs in
 * `failure_detail`, never in the label.
 */
object FailureClassifier {

    const val PAYMENT_DECLINED = "payment_declined"
    const val PAYMENT_TOKEN_MISSING = "payment_token_missing"

    /** A reversal that Payments definitively refused. The refusing code goes in the detail. */
    const val PAYMENT_REVERSAL_FAILED = "payment_reversal_failed"

    const val CANCELLED = "cancelled"
    const val UNKNOWN = "unknown"

    /**
     * Label for an Inventory business refusal. The enum names are already the frozen SCREAMING_SNAKE
     * codes from the API contract, so lowercasing keeps the label traceable to the wire code with no
     * second mapping table to drift out of sync.
     */
    fun forInventoryRefusal(code: InventoryRefusalCode): String = code.name.lowercase()
}
