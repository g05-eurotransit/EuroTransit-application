package it.polito.cpo.repository

import it.polito.cpo.model.ReconciliationRecord
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ReconciliationRecordRepository : CoroutineCrudRepository<ReconciliationRecord, UUID> {

    /**
     * The single write path for opening or recurring an incident.
     *
     * Idempotent by the partial unique index on `(order_id, kind) WHERE status = 'OPEN'`, so two
     * concurrent writers for the same order converge on one row instead of racing a SELECT then
     * INSERT. Returns the resulting row so the caller can read `attemptCount` and tell a first
     * occurrence from a recurrence.
     *
     * `ON CONFLICT (...) WHERE ...` restates the index predicate, which is how PostgreSQL infers a
     * partial unique index as the conflict target.
     */
    @Query(
        """
        INSERT INTO reconciliation_records
            (id, order_id, kind, status, order_state, correlation_id,
             failure_class, failure_detail, attempt_count, created_at, updated_at)
        VALUES
            (:id, :orderId, :kind, 'OPEN', :orderState, :correlationId,
             :failureClass, :failureDetail, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (order_id, kind) WHERE status = 'OPEN'
        DO UPDATE SET
            attempt_count  = reconciliation_records.attempt_count + 1,
            order_state    = EXCLUDED.order_state,
            correlation_id = EXCLUDED.correlation_id,
            failure_class  = EXCLUDED.failure_class,
            failure_detail = EXCLUDED.failure_detail,
            updated_at     = CURRENT_TIMESTAMP
        RETURNING *
        """,
    )
    suspend fun upsertOpen(
        id: UUID,
        orderId: UUID,
        kind: String,
        orderState: String,
        correlationId: String,
        failureClass: String,
        failureDetail: String?,
    ): ReconciliationRecord

    /**
     * Closes the kinds that a terminal Orders state resolves by definition. Must run inside the SAME
     * transaction as the terminal transition.
     *
     * INVARIANT_VIOLATION is excluded on purpose: the order failing does not explain how the
     * impossible state arose, so it needs an operator. PAYMENT_REVERSAL_FAILURE is included because
     * Orders only ever reaches a terminal state after the reversal succeeded. SEAT_RETURN_FAILURE is
     * included for the same reason: REFUNDED is only ever committed after the seat return succeeded
     * (RefundOrchestrator), so reaching it here means a previously-stuck seat return has since been
     * resolved — by a redelivery, or by an operator retrying it. `REFUNDING` is not itself a
     * "terminal transition" in the money sense, so this call must run from the REFUNDING -> REFUNDED
     * commit specifically, not from entering REFUNDING.
     */
    @Modifying
    @Query(
        """
        UPDATE reconciliation_records
           SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
               resolved_by = 'system:terminal-state', updated_at = CURRENT_TIMESTAMP
         WHERE order_id = :orderId AND status = 'OPEN'
           AND kind IN ('DELIVERY_EXHAUSTED', 'PAYMENT_REVERSAL_FAILURE', 'SEAT_RETURN_FAILURE')
        """,
    )
    suspend fun closeAutoResolvable(orderId: UUID): Long

    /**
     * Operator resolution. The WHERE clause is the enforcement of "cannot waive a pending payment
     * reversal": this statement can only ever touch an open INVARIANT_VIOLATION.
     */
    @Modifying
    @Query(
        """
        UPDATE reconciliation_records
           SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
               resolved_by = :resolvedBy, evidence_reference = :evidenceReference,
               updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND order_id = :orderId
           AND kind = 'INVARIANT_VIOLATION' AND status = 'OPEN'
        """,
    )
    suspend fun resolveInvariantViolation(
        id: UUID,
        orderId: UUID,
        resolvedBy: String,
        evidenceReference: String,
    ): Long

    /** Records an operator redrive attempt against every open incident for the order. */
    @Modifying
    @Query(
        """
        UPDATE reconciliation_records
           SET attempt_count = attempt_count + 1, correlation_id = :correlationId,
               updated_at = CURRENT_TIMESTAMP
         WHERE order_id = :orderId AND status = 'OPEN'
        """,
    )
    suspend fun touchRedrive(orderId: UUID, correlationId: String): Long

    fun findAllByOrderIdAndStatus(orderId: UUID, status: String): Flow<ReconciliationRecord>

    @Query("SELECT COUNT(*) FROM reconciliation_records WHERE status = 'OPEN' AND kind = :kind")
    suspend fun countOpenByKind(kind: String): Long

    @Query(
        """
        SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))), 0)
          FROM reconciliation_records WHERE status = 'OPEN'
        """,
    )
    suspend fun oldestOpenAgeSeconds(): Double
}
