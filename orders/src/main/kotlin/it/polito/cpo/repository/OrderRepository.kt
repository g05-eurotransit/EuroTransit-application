package it.polito.cpo.repository

import it.polito.cpo.model.Order
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
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
    @Modifying
    @Query(
        """
        UPDATE orders SET status = 'PAYMENT_PENDING', reservation_id = :reservationId
        WHERE id = :id
          AND (status = 'RESERVING'
               OR (status = 'PAYMENT_PENDING' AND reservation_id = :reservationId))
        """,
    )
    suspend fun markPaymentPending(id: UUID, reservationId: String): Long

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
        UPDATE orders SET status = 'FAILED', payment_method_token = NULL
        WHERE id = :id AND status IN ('RESERVING', 'PAYMENT_PENDING')
        """,
    )
    suspend fun markFailedUnpaid(id: UUID): Long

    // FAILED after authorization, reachable only from COMPENSATING, and the orchestrator calls it
    // only once the reversal has definitively succeeded. There is deliberately NO SQL path from
    // CONFIRMING_INVENTORY to FAILED, so "FAILED after authorization only when the payment reversal
    // has completed successfully" (reservation-confirmation.md:150-152) is enforced by the shape of
    // this API rather than by a comment.
    @Modifying
    @Query(
        """
        UPDATE orders SET status = 'FAILED', payment_method_token = NULL
        WHERE id = :id AND status = 'COMPENSATING'
        """,
    )
    suspend fun markFailedAfterReversal(id: UUID): Long

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
}
