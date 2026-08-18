package it.polito.cpo.contracts.events

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

// Common Kafka event envelope per docs/design/api-design.md, aligned with OrderEvents/InventoryEvents:
// fixed metadata (incl. schemaVersion) + an event-specific `payload` block.
//
// Note: payments handles orderId as a String end-to-end (IPaymentService.authorize(orderId: String),
// PaymentAuthorization.orderId: String), so the envelope keeps orderId as String here rather than the
// UUID used by OrderEvents — avoiding a fragile UUID.fromString on the emit path.

data class PaymentAuthorizationEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String, // "payment-authorized" | "payment-declined"
    val schemaVersion: Int = 1,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    val correlationId: String,
    val orderId: String,
    val principalId: String,
    val payload: PaymentAuthorizationPayload
)

data class PaymentAuthorizationPayload(
    val status: String,
    val providerReference: String?,
    val amount: BigDecimal,
    val currency: String,
    val errorCode: String? = null
)

data class PaymentRefundEvent(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String, // "payment-refunded" | "payment-refund-failed"
    val schemaVersion: Int = 1,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
    val correlationId: String,
    val orderId: String,
    val principalId: String,
    val payload: PaymentRefundPayload
)

data class PaymentRefundPayload(
    val status: String,
    val refundReference: String?,
    val amount: BigDecimal?,
    val currency: String,
    val errorCode: String? = null
)
