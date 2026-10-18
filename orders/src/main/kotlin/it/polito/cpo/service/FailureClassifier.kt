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

    /**
     * An abandoned checkout for which Payments holds no settled authorization (#140): the money may or
     * may not have been taken. The code Payments answered goes in the detail.
     */
    const val PAYMENT_OUTCOME_UNKNOWN = "payment_outcome_unknown"

    /** A reversal that Payments definitively refused. The refusing code goes in the detail. */
    const val PAYMENT_REVERSAL_FAILED = "payment_reversal_failed"

    const val CANCELLED = "cancelled"

    /** A checkout an operator abandoned past its seat hold (#140), settled by compensation. */
    const val CHECKOUT_ABANDONED = "checkout_abandoned"
    const val UNKNOWN = "unknown"

    const val POISON_PAYLOAD = "poison_payload"
    const val DEPENDENCY_ERROR = "dependency_error"

    /**
     * Label for an Inventory business refusal. The enum names are already the frozen SCREAMING_SNAKE
     * codes from the API contract, so lowercasing keeps the label traceable to the wire code with no
     * second mapping table to drift out of sync.
     */
    fun forInventoryRefusal(code: InventoryRefusalCode): String = code.name.lowercase()

    /**
     * Label for an exhausted delivery, derived from the exception class the dead-letter headers carry.
     *
     * Mapped through a closed set rather than passing the class name through: the exception type comes
     * from a header a producer controls, so using it directly would make the metric cardinality
     * unbounded and the column unqueryable.
     *
     * Classify the **cause** when there is one. The listener container wraps whatever the listener
     * threw in a `ListenerExecutionFailedException` before the recoverer sees it, so the recoverer's
     * `DLT_EXCEPTION_FQCN` is always that wrapper and the class that says what went wrong is in
     * `DLT_EXCEPTION_CAUSE_FQCN`. Reading only the first labelled every incident `unknown`
     * (`DeadLetterHeadersTest`).
     */
    fun forDeadLetter(exceptionFqcn: String?, causeFqcn: String? = null): String =
        classifyDeadLetterClass(causeFqcn ?: exceptionFqcn)

    /**
     * Every label [forDeadLetter] can return. Kept beside it so the two cannot drift: the dead-letter
     * counter pre-registers one series per label, and a label missing here would be a series that
     * appears at 1 — invisible to `increase()` (`FailureClassifierTest` pins the set).
     */
    val DEAD_LETTER_LABELS = listOf(POISON_PAYLOAD, DEPENDENCY_ERROR, UNKNOWN)

    /**
     * Every `reason` a failed checkout can be counted under (`orders.checkout.failed`), for the same
     * reason as [DEAD_LETTER_LABELS]: the series are pre-registered at zero, so the canary's
     * failure-rate gate sees the first failure on a new pod. `failUnpaid` records an Inventory refusal
     * or one of the two payment labels; the end of compensation records the refusal that triggered it,
     * or [UNKNOWN]. Derived from [InventoryRefusalCode] so a new code joins without an edit here.
     */
    val CHECKOUT_FAILURE_REASONS: List<String> =
        listOf(PAYMENT_DECLINED, PAYMENT_TOKEN_MISSING, CHECKOUT_ABANDONED, UNKNOWN) +
            InventoryRefusalCode.entries.map { forInventoryRefusal(it) }

    private fun classifyDeadLetterClass(exceptionFqcn: String?): String = when {
        exceptionFqcn == null -> UNKNOWN
        exceptionFqcn.endsWith("PoisonEventException") -> POISON_PAYLOAD
        exceptionFqcn.endsWith("TimeoutException") -> DEPENDENCY_ERROR
        exceptionFqcn.contains("WebClient") -> DEPENDENCY_ERROR
        exceptionFqcn.contains("CallNotPermitted") -> DEPENDENCY_ERROR
        // A saturated bulkhead refuses with BulkheadFullException (maxWaitDuration = ZERO): the same
        // "dependency not available right now" as an open breaker, not an unexplained failure.
        exceptionFqcn.contains("BulkheadFull") -> DEPENDENCY_ERROR
        // The service token could not be obtained from Keycloak (an error response; a connection
        // failure already surfaces as a WebClient exception). The call never reached its target.
        exceptionFqcn.contains("ClientAuthorization") -> DEPENDENCY_ERROR
        exceptionFqcn.contains("R2dbc") || exceptionFqcn.contains("DataAccess") -> DEPENDENCY_ERROR
        else -> UNKNOWN
    }
}
