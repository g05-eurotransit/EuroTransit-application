package it.polito.cpo.contracts.events

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// Common Kafka event envelope per docs/design/api-design.md:
// fixed metadata (incl. schemaVersion) + an event-specific `payload` block.

data class OrderPlacedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String = "order-placed",
    val schemaVersion: Int = 1,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    val correlationId: String,
    val orderId: UUID,
    val principalId: String,
    val payload: Map<String, Any?> = emptyMap()
)

data class OrderConfirmedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String = "order-confirmed",
    val schemaVersion: Int = 1,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    val correlationId: String,
    val orderId: UUID,
    val principalId: String,
    val payload: Map<String, Any?> = emptyMap()
)

data class NotificationRequestedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String = "notification-requested",
    val schemaVersion: Int = 1,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    val correlationId: String,
    val orderId: UUID,
    val principalId: String,
    val payload: NotificationRequestedPayload
)

/** What happened to the order, as a closed set a client can branch on. */
enum class NotificationType { ORDER_CONFIRMED, ORDER_FAILED }

/**
 * Everything past [message] is nullable with a default, and deliberately so.
 *
 * Orders and Notifications are deployed separately, so an event produced by the previous version
 * has to keep parsing: a consumer that saw no [type] renders exactly what it rendered before, the
 * plain [message]. That property is what makes the rollout safe in either order, and it is asserted
 * by a test rather than left to hold by accident.
 */
data class NotificationRequestedPayload(
    val recipientEmail: String,
    /**
     * A complete, ready-to-display sentence. It is the fallback whenever a client cannot compose
     * something better, and it is the only place that says anything about money — those claims are
     * owned by Orders' closed `CustomerFailureCode` set and must not be rewritten downstream.
     */
    val message: String,
    val type: NotificationType? = null,
    /** The `CustomerFailureCode` name. Null on a confirmation — nothing failed. */
    val failureCode: String? = null,
    // The trip, carried as identifiers rather than as resolved names: Orders has no Catalog client
    // and must not acquire one for this, because a confirmation cannot be made to depend on Catalog
    // being up. The consumer resolves them.
    val routeId: String? = null,
    val travelDate: LocalDate? = null,
)
