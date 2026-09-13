package it.polito.cpo.repository

import it.polito.cpo.model.Order
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

interface OrderRepository : CoroutineCrudRepository<Order, UUID> {

    // --- Race-free money-path transitions --------------------------------------------------------
    // The async pipeline (Kafka consumer thread) and a customer cancellation (web request) run
    // concurrently, and the reserve/authorize/confirm calls are suspension points. Advancing the
    // order with a full-entity save would clobber a concurrent cancellation, so every transition is
    // a single atomic UPDATE that asserts the expected predecessor state and returns rows affected.
    //
    // Two shapes, and the difference is load-bearing:
    //
    //  * PHASE MARKERS (RESERVING, PAYMENT_PENDING, CONFIRMING_INVENTORY, COMPENSATING) have no
    //    side effect beyond the row, so they include the target state in the predicate. A Kafka
    //    redelivery that repeats a completed stage therefore still sees 1 row and proceeds;
    //    rewriting the same value is harmless.
    //
    //  * TERMINAL COMMITS (CONFIRMED, FAILED) are paired with outbox rows, so they EXCLUDE the
    //    self-transition. Under a concurrent double delivery exactly one wins and the events are
    //    written exactly once; 0 rows forces the caller to re-read and distinguish "already done"
    //    from "unexpected state" instead of silently duplicating order-confirmed.
    //
    // CANCELLED never appears in a forward predicate, so a cancellation that commits mid-pipeline
    // still makes the next transition affect 0 rows and abort the pipeline.

    @Modifying
    @Query("UPDATE orders SET status = 'RESERVING' WHERE id = :id AND status IN ('ACCEPTED', 'RESERVING')")
    suspend fun markReserving(id: UUID): Long

    // The self-transition is additionally pinned to the SAME reservation id. An idempotent hold
    // replay (inv-{orderId}) always resolves the original reservation, so a *different* id means the
    // hold contract was violated: 0 rows, and the caller treats it as an invariant violation rather
    // than silently rebinding the order to another hold.
    // The deadline is written in the SAME statement as the id, not in a follow-up update: they are
    // two halves of one fact, and a crash between two writes would leave an order holding seats with
    // no visible expiry, or an expiry belonging to a hold it no longer has.
    @Modifying
    @Query(
        """
        UPDATE orders
           SET status = 'PAYMENT_PENDING',
               reservation_id = :reservationId,
               reservation_expires_at = :reservationExpiresAt
        WHERE id = :id
          AND (status = 'RESERVING'
               OR (status = 'PAYMENT_PENDING' AND reservation_id = :reservationId))
        """,
    )
    suspend fun markPaymentPending(
        id: UUID,
        reservationId: String,
        reservationExpiresAt: OffsetDateTime,
    ): Long

    @Modifying
    @Query(
        """
        UPDATE orders SET status = 'CONFIRMING_INVENTORY'
        WHERE id = :id AND status IN ('PAYMENT_PENDING', 'CONFIRMING_INVENTORY')
        """,
    )
    suspend fun markConfirmingInventory(id: UUID): Long

    // Reachable only from CONFIRMING_INVENTORY: compensation exists only after authorization.
    @Modifying
    @Query(
        """
        UPDATE orders SET status = 'COMPENSATING', compensation_reason = :reason
        WHERE id = :id AND status IN ('CONFIRMING_INVENTORY', 'COMPENSATING')
        """,
    )
    suspend fun markCompensating(id: UUID, reason: String): Long

    @Modifying
    @Query(
        """
        UPDATE orders SET status = 'CONFIRMED', payment_method_token = NULL
        WHERE id = :id AND status = 'CONFIRMING_INVENTORY'
        """,
    )
    suspend fun markConfirmed(id: UUID): Long

    // FAILED at or before the payment decision: no authorized money exists, so no reversal is due.
    @Modifying
    @Query(
        """
        UPDATE orders SET status = 'FAILED', payment_method_token = NULL, failure_code = :failureCode
        WHERE id = :id AND status IN ('RESERVING', 'PAYMENT_PENDING')
        """,
    )
    suspend fun markFailedUnpaid(id: UUID, failureCode: String): Long

    // FAILED after authorization, reachable only from COMPENSATING, and the orchestrator calls it
    // only once the reversal has definitively succeeded. There is deliberately NO SQL path from
    // CONFIRMING_INVENTORY to FAILED, so "FAILED after authorization only when the payment reversal
    // has completed successfully" (reservation-confirmation.md:150-152) is enforced by the shape of
    // this API rather than by a comment.
    @Modifying
    @Query(
        """
        UPDATE orders SET status = 'FAILED', payment_method_token = NULL, failure_code = :failureCode
        WHERE id = :id AND status = 'COMPENSATING'
        """,
    )
    suspend fun markFailedAfterReversal(id: UUID, failureCode: String): Long

    // Customer cancellation is accepted only while no payment authorization has started
    // (reservation-confirmation.md:147-149). From PAYMENT_PENDING onward the controller answers
    // 409 ORDER_NOT_CANCELLABLE. Closing the race before authorization is what makes every
    // post-authorization phase single-writer.
    @Modifying
    @Query(
        """
        UPDATE orders SET status = 'CANCELLED', payment_method_token = NULL
        WHERE id = :id AND status IN ('ACCEPTED', 'RESERVING')
        """,
    )
    suspend fun cancelIfUnpaid(id: UUID): Long

    // --- Post-sale refund (ADR-015) -----------------------------------------------------------------
    // A separate durable process from the checkout pipeline above, entered only from CONFIRMED. Money
    // and seat are reversed by RefundOrchestrator's own consumer, never by this repository — these two
    // statements only gate and record the state transition itself.

    /**
     * Atomically transitions CONFIRMED -> REFUNDING, gated on the one condition that can change
     * between the moment `RefundOrchestrator.refund` reads the order and the moment this statement
     * runs: the cutoff. `offer_id IS NOT NULL` and `refundable = TRUE` are included too, but only as a
     * second line of defence — neither can change once V13/V14 write them at checkout, so the
     * endpoint's own pre-check has already refused a request that fails them; this predicate exists so
     * a bug in that pre-check cannot let one through regardless.
     *
     * The cutoff is compared against `CURRENT_TIMESTAMP`, never a value read into the JVM, for the
     * same reason invariant 4 evaluates hold expiry against the database clock (ADR-015): replicas
     * disagree by seconds, and at the boundary two identical requests to two pods must get the same
     * answer. `COALESCE(refund_cutoff_minutes_before_departure, 0)` treats a null cutoff as "no
     * cutoff", i.e. refundable right up to `departure_at` itself, matching `OrderView`'s contract.
     *
     * 0 rows means either the cutoff has now passed or a concurrent request already moved the order
     * past CONFIRMED; the caller re-reads to tell the two apart, exactly as `commitConfirmed` does
     * after `markConfirmed`.
     */
    @Modifying
    @Query(
        """
        UPDATE orders
           SET status = 'REFUNDING', refund_requested_at = CURRENT_TIMESTAMP
         WHERE id = :id
           AND status = 'CONFIRMED'
           AND offer_id IS NOT NULL
           AND refundable = TRUE
           AND departure_at IS NOT NULL
           AND CURRENT_TIMESTAMP <
               (departure_at - (COALESCE(refund_cutoff_minutes_before_departure, 0) * INTERVAL '1 minute'))
        """,
    )
    suspend fun markRefunding(id: UUID): Long

    // Terminal commit for a completed refund. No self-transition, like markConfirmed: this is paired
    // with closing the refund's own reconciliation kinds in the SAME transaction (closeAutoResolvable),
    // so a concurrent double delivery of refund-requested must not run that twice.
    @Modifying
    @Query(
        """
        UPDATE orders
           SET status = 'REFUNDED', refunded_at = CURRENT_TIMESTAMP,
               refund_provider_reference = :providerReference
         WHERE id = :id AND status = 'REFUNDING'
        """,
    )
    suspend fun markRefunded(id: UUID, providerReference: String?): Long

    // --- Customer order history ------------------------------------------------------------------
    // Ownership is enforced HERE, in the predicate, and not in the controller. `getOrder` and
    // `cancel` let the `operations` role bypass their ownership check, which is right for a single
    // order a support agent was given the id of; reusing that shape on a list would hand every
    // customer's orders to any operator holding the role. A privileged search, if it is ever wanted,
    // is a separate endpoint with its own authorization.
    //
    // Pagination is keyset, not offset: an offset page shifts under concurrent inserts, and orders
    // are inserted exactly while a customer is looking at this list. The sort key is
    // (created_at, id) because created_at is not unique.
    //
    // The status filter is optional. `CAST(:status AS VARCHAR)` is what makes a null bind legal:
    // without the cast Postgres cannot infer the type of the parameter in `IS NULL`.

    @Query(
        """
        SELECT * FROM orders
         WHERE user_id = :userId
           AND (CAST(:status AS VARCHAR) IS NULL OR status = CAST(:status AS VARCHAR))
         ORDER BY created_at DESC, id DESC
         LIMIT :limit
        """,
    )
    fun findHistoryFirstPage(userId: String, status: String?, limit: Int): Flow<Order>

    @Query(
        """
        SELECT * FROM orders
         WHERE user_id = :userId
           AND (CAST(:status AS VARCHAR) IS NULL OR status = CAST(:status AS VARCHAR))
           AND (created_at, id) < (:cursorCreatedAt, :cursorId)
         ORDER BY created_at DESC, id DESC
         LIMIT :limit
        """,
    )
    fun findHistoryAfter(
        userId: String,
        status: String?,
        cursorCreatedAt: LocalDateTime,
        cursorId: UUID,
        limit: Int,
    ): Flow<Order>

    /**
     * Feeds the non-terminal pipeline gauges. An order stranded in one of these phases is the failure
     * mode the synchronous confirmation introduces — money is committed somewhere and the checkout has
     * not converged — so it needs to be observable by age, not just by count.
     *
     * `created_at` is `TIMESTAMP WITHOUT TIME ZONE`, so the age is computed against `LOCALTIMESTAMP`.
     * Comparing it with `CURRENT_TIMESTAMP` would silently offset every age by the session time zone.
     */
    @Query(
        """
        SELECT status                                                              AS status,
               COUNT(*)                                                            AS order_count,
               COALESCE(MAX(EXTRACT(EPOCH FROM (LOCALTIMESTAMP - created_at))), 0) AS oldest_age_seconds
          FROM orders
         WHERE status IN ('PAYMENT_PENDING', 'CONFIRMING_INVENTORY', 'COMPENSATING')
         GROUP BY status
        """,
    )
    fun countNonTerminalByState(): Flow<NonTerminalStateRow>
}

/** One row of [OrderRepository.countNonTerminalByState]. */
data class NonTerminalStateRow(
    val status: String,
    val orderCount: Long,
    val oldestAgeSeconds: Double,
)
