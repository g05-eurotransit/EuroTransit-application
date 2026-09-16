package it.polito.cpo.notification

import it.polito.cpo.contracts.events.NotificationType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * A delivered notification, used both as the in-memory record and the API/SSE response.
 * `principalId` is the ownership key (JWT `sub`); it is used server-side to filter
 * history and stream, and is echoed in responses.
 *
 * [route] gives a client the trip context to render its own sentence consistently, instead of
 * depending on backend wording. [message] stays the ready-to-display fallback and remains the only
 * field that says anything about money — those claims belong to Orders' closed failure-code set and
 * a client must not restate them.
 *
 * [type], [failureCode] and [route] are all nullable: an event produced before this contract
 * existed carries none of them, and such a notification renders exactly as it did before.
 */
data class NotificationView(
    val id: UUID,
    val principalId: String,
    val orderId: UUID,
    val message: String,
    val recipientEmail: String,
    val occurredAt: LocalDateTime,
    val receivedAt: LocalDateTime,
    val type: NotificationType? = null,
    /** The `CustomerFailureCode` name. Branch on this, never on an HTTP status. */
    val failureCode: String? = null,
    val route: RouteView? = null,
)

/** The trip, as the customer recognises it. Times are local to the departure station. */
data class RouteView(
    val routeId: String,
    val origin: String,
    val destination: String,
    val travelDate: LocalDate,
    val departureTime: LocalTime,
)
