package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationRecord
import it.polito.cpo.repository.ReconciliationRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.UUID

/**
 * Opens, recurs and closes the typed reconciliation records that carry an incident to an operator.
 *
 * Two families of method, and the distinction is load-bearing:
 *
 *  * **transaction-joining** ([recordInvariantViolation], [closeAutoResolvable]) deliberately open no
 *    transaction of their own. They are called from inside the orchestrator's
 *    `txOperator.executeAndAwait { ... }` and join it through the Reactor context, which is the same
 *    mechanism the outbox writes already rely on. An INVARIANT_VIOLATION that committed while the
 *    COMPENSATING transition rolled back would be an incident about a state the order never entered.
 *
 *  * **self-transacting** ([recordPaymentReversalFailure]) own their transaction, because they are
 *    not paired with a state change: the order stays exactly where it is.
 *
 * Metrics are incremented only after the surrounding transaction has returned, mirroring the
 * commit-aware counters in the Inventory service. A record that rolled back must not alert.
 */
@Service
class ReconciliationService(
    private val records: ReconciliationRecordRepository,
    private val meterRegistry: MeterRegistry,
    private val txOperator: TransactionalOperator,
) {
    private val logger = LoggerFactory.getLogger(ReconciliationService::class.java)

    /**
     * Records that Inventory reported a state the model says cannot happen. Joins the caller's
     * transaction; the caller increments the metric with [countRequired] once that transaction has
     * committed.
     */
    suspend fun recordInvariantViolation(
        orderId: UUID,
        orderState: String,
        correlationId: String,
        failureClass: String,
        detail: String?,
    ): ReconciliationRecord = upsert(
        orderId = orderId,
        kind = ReconciliationKind.INVARIANT_VIOLATION,
        orderState = orderState,
        correlationId = correlationId,
        failureClass = failureClass,
        detail = detail,
    )

    /**
     * Records that a payment reversal definitively failed. Self-transacting, and it deliberately does
     * NOT move the order: unresolved money must stay non-terminal (doc:306).
     */
    suspend fun recordPaymentReversalFailure(
        orderId: UUID,
        orderState: String,
        correlationId: String,
        failureClass: String,
        detail: String?,
    ): ReconciliationRecord {
        val record = txOperator.executeAndAwait {
            upsert(
                orderId = orderId,
                kind = ReconciliationKind.PAYMENT_REVERSAL_FAILURE,
                orderState = orderState,
                correlationId = correlationId,
                failureClass = failureClass,
                detail = detail,
            )
        }!!
        countRequired(record)
        return record
    }

    /**
     * Closes the incidents that a terminal order resolves by definition. Must be called from inside
     * the terminal transaction so the close and the terminal state commit together.
     */
    suspend fun closeAutoResolvable(orderId: UUID): Long = records.closeAutoResolvable(orderId)

    /**
     * Commit-aware alert signal. A counter alone cannot express "alert while any record is open"
     * beyond its rate window, so the open-record gauge is the companion to this, not a nicety.
     */
    fun countRequired(record: ReconciliationRecord) {
        meterRegistry.counter(
            "orders.reconciliation.required",
            "kind", record.kind,
            "failure_class", record.failureClass,
        ).increment()
        logger.error(
            "Reconciliation required for order {}: kind={} failureClass={} state={} attempt={} correlationId={}",
            record.orderId,
            record.kind,
            record.failureClass,
            record.orderState,
            record.attemptCount,
            record.correlationId,
        )
    }

    private suspend fun upsert(
        orderId: UUID,
        kind: ReconciliationKind,
        orderState: String,
        correlationId: String,
        failureClass: String,
        detail: String?,
    ): ReconciliationRecord = records.upsertOpen(
        id = UUID.randomUUID(),
        orderId = orderId,
        kind = kind.name,
        orderState = orderState,
        correlationId = correlationId,
        failureClass = failureClass,
        failureDetail = detail?.take(MAX_DETAIL),
    )

    private companion object {
        /** Matches the `failure_detail VARCHAR(1024)` column. */
        const val MAX_DETAIL = 1024
    }
}
