package it.polito.cpo.repository

import it.polito.cpo.model.OpenReconciliationIncident
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
           AND kind IN ('DELIVERY_EXHAUSTED', 'PAYMENT_REVERSAL_FAILURE', 'SEAT_RETURN_FAILURE',
                        'PAYMENT_OUTCOME_UNKNOWN')
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

    /**
     * Operator settlement of an unknown payment outcome (#140): the operator attests, from the provider,
     * that no money is held for the order. Filtered to an open PAYMENT_OUTCOME_UNKNOWN of this order,
     * the only kind it can close.
     */
    @Modifying
    @Query(
        """
        UPDATE reconciliation_records
           SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP,
               resolved_by = :resolvedBy, evidence_reference = :evidenceReference,
               updated_at = CURRENT_TIMESTAMP
         WHERE id = :id AND order_id = :orderId
           AND kind = 'PAYMENT_OUTCOME_UNKNOWN' AND status = 'OPEN'
        """,
    )
    suspend fun settlePaymentOutcomeUnknown(
        id: UUID,
        orderId: UUID,
        resolvedBy: String,
        evidenceReference: String,
    ): Long

    /**
     * Whether an operator has settled this order's unknown payment outcome with evidence. Read by the
     * compensation, which only then fails an abandoned order whose authorization Payments cannot find.
     * `resolved_by` excludes the automatic close, which never carries evidence.
     */
    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM reconciliation_records
             WHERE order_id = :orderId AND kind = 'PAYMENT_OUTCOME_UNKNOWN' AND status = 'RESOLVED'
               AND evidence_reference IS NOT NULL AND resolved_by <> 'system:terminal-state'
        )
        """,
    )
    suspend fun isPaymentOutcomeSettled(orderId: UUID): Boolean

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

    /**
     * The open incidents for the operator list, most urgent first: those whose order still has a live
     * seat hold, the nearest deadline first; then those with no hold deadline, oldest first; then those
     * past their hold, which include the ones nothing will close (an expired `PAYMENT_PENDING`, an
     * invariant violation). Sorting only by age would let those pile up at the top and push a fresh,
     * still redrivable incident off the page.
     *
     * A left join: an incident is only opened for a known order, but a list that silently dropped one
     * would hide exactly the incident nobody can explain.
     */
    @Query(
        """
        SELECT r.id, r.order_id, r.kind, r.order_state, o.status AS order_status, r.failure_class,
               r.attempt_count, r.created_at, r.updated_at, o.reservation_expires_at
          FROM reconciliation_records r
          LEFT JOIN orders o ON o.id = r.order_id
         WHERE r.status = 'OPEN'
         ORDER BY CASE
                      WHEN o.reservation_expires_at > CURRENT_TIMESTAMP THEN 0
                      WHEN o.reservation_expires_at IS NULL THEN 1
                      ELSE 2
                  END,
                  o.reservation_expires_at, r.created_at, r.id
         LIMIT :limit
        """,
    )
    fun findOpenIncidents(limit: Int): Flow<OpenReconciliationIncident>

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
