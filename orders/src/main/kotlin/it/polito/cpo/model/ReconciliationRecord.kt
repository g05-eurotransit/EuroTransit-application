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
     * with the order in COMPENSATING — money is unresolved, so the order must not read as finished.
     */
    PAYMENT_REVERSAL_FAILURE,
}

enum class ReconciliationStatus { OPEN, RESOLVED }
