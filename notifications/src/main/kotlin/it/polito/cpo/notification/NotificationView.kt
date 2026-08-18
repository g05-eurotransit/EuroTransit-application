package it.polito.cpo.notification

import java.time.LocalDateTime
import java.util.UUID

/**
 * A delivered notification, used both as the in-memory record and the API/SSE response.
 * `principalId` is the ownership key (JWT `sub`); it is used server-side to filter
 * history and stream, and is echoed in responses.
 */
data class NotificationView(
    val id: UUID,
    val principalId: String,
    val orderId: UUID,
    val message: String,
    val recipientEmail: String,
    val occurredAt: LocalDateTime,
    val receivedAt: LocalDateTime,
)
