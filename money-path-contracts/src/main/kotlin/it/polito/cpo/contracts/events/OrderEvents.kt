package it.polito.cpo.contracts.events

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

data class NotificationRequestedPayload(
    val recipientEmail: String,
    val message: String
)
