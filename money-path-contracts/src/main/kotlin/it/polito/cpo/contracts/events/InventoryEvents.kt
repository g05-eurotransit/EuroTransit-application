package it.polito.cpo.contracts.events

import java.time.LocalDateTime
import java.util.UUID

data class InventoryReservedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String = "inventory-reserved",
    val schemaVersion: Int = 1,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    val correlationId: String,
    val orderId: UUID,
    val principalId: String,
    val payload: InventoryReservedPayload
)

data class InventoryReservedPayload(
    val reservationId: String,
    val routeId: String,
    val expiresAt: LocalDateTime
)

data class InventoryFailedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String = "inventory-failed",
    val schemaVersion: Int = 1,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    val correlationId: String,
    val orderId: UUID,
    val principalId: String,
    val payload: InventoryFailedPayload
)

data class InventoryFailedPayload(
    val reason: String
)
