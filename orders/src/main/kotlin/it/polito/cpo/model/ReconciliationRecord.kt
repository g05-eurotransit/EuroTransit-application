package it.polito.cpo.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * An operational incident on the Orders money path that automatic recovery could not resolve.
 *
 * Deliberately NOT a [org.springframework.data.domain.Persistable], unlike [Order] and [OutboxEvent]:
 * every write goes through explicit SQL in [it.polito.cpo.repository.ReconciliationRecordRepository]
 * (the insert-or-recur upsert, the terminal close, the operator resolution), so nothing here ever
 * needs Spring Data to decide between an insert and an update. Carrying a `@Transient` new-entity flag
 * as a constructor parameter would also break the mapper outright, since it has no property to read
 * back when materialising a row.
 */
@Table("reconciliation_records")
data class ReconciliationRecord(
    @Id val id: UUID = UUID.randomUUID(),
    val orderId: UUID,
    val kind: String,
    val status: String = ReconciliationStatus.OPEN.name,
    val orderState: String,
    val correlationId: String,
    val failureClass: String,
    val failureDetail: String? = null,
    val attemptCount: Int = 1,
    val evidenceReference: String? = null,
    val resolvedBy: String? = null,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val updatedAt: OffsetDateTime = OffsetDateTime.now(),
    val resolvedAt: OffsetDateTime? = null,
)

/**
 * The fixed set of reconciliation kinds (docs/design/reservation-confirmation.md:337-345). Each has a
 * different resolution path, which is why they are not collapsed into a single "needs attention".
 */
enum class ReconciliationKind {
    /** A valid event exhausted redelivery and reached `order-placed.DLT`. Closes on redrive. */
    DELIVERY_EXHAUSTED,

    /**
     * Missing reservation, order ownership conflict, or seat-count conflict. Payment is reversed and
     * the order fails, but the record stays open until an operator investigates: something happened
     * that the state model says cannot happen.
     */
    INVARIANT_VIOLATION,

    /**
     * A provider reversal returned a definitive failure or repeatedly remained unknown. Stays open
     * with the order in COMPENSATING (checkout compensation) or REFUNDING (a post-sale refund) —
     * money is unresolved either way, so the order must not read as finished.
     */
    PAYMENT_REVERSAL_FAILURE,

    /**
     * A post-sale refund (ADR-015) reversed the money but Inventory definitively refused to return
     * the seat. Stays open with the order in REFUNDING: the customer already has their money, and one
     * resaleable seat is withheld until an operator looks. There is no checkout-side counterpart —
     * the checkout pipeline releases a hold, it never asks Inventory to un-sell one.
     */
    SEAT_RETURN_FAILURE,

    /**
     * An operator abandoned a `PAYMENT_PENDING` order past its seat hold (#140), and Payments holds no
     * settled authorization to reverse: none at all, or one that is not `AUTHORIZED`. That does not
     * prove no money was taken — Payments calls the provider before it writes its row, so a charge
     * whose answer was lost leaves none. Stays open with the order in `COMPENSATING` until an operator
     * checks the provider and settles it with evidence (`.../settle`); the pipeline then fails the
     * order. Also closes by itself if a later redrive finds the authorization and reverses it.
     */
    PAYMENT_OUTCOME_UNKNOWN,
}

enum class ReconciliationStatus { OPEN, RESOLVED }

/**
 * An open incident as an operator lists it (`GET /api/orders/reconciliation`): enough to choose and
 * time a redrive, and nothing a caller should not see.
 *
 * Two states, on purpose: [orderState] is the order's phase when the incident was opened or last
 * recurred, which is what failed; [orderStatus] is where the order is now, which is what a redrive
 * resumes from. They differ for an invariant violation (opened in `COMPENSATING`, the order then
 * `FAILED`) and for any order that moved after its incident. `failureDetail` is left out because it is free
 * text from a dependency's error; the order's own data stays behind `GET /api/orders/{orderId}`.
 *
 * [reservationExpiresAt] is the order's seat-hold deadline, when it has one: past it, a
 * `PAYMENT_PENDING` order can no longer be redriven (`RESERVATION_HOLD_EXPIRED`), so it is the clock
 * an operator works against.
 *
 * A read projection joined from `reconciliation_records` and `orders`, not an entity.
 */
data class OpenReconciliationIncident(
    val id: UUID,
    val orderId: UUID,
    val kind: String,
    val orderState: String,
    val orderStatus: String?,
    val failureClass: String,
    val attemptCount: Int,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val reservationExpiresAt: OffsetDateTime?,
)
