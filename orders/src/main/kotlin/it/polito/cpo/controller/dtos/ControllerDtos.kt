package it.polito.cpo.controller.dtos

import it.polito.cpo.model.OrderStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

data class CheckoutRequest(
    val routeId: String,
    val seats: List<String>,
    val totalAmount: BigDecimal,
    val paymentMethodToken: String
)

data class CheckoutResponse(
    val orderId: UUID,
    val status: OrderStatus
)

data class OrderView(
    val orderId: UUID,
    val userId: String,
    val status: OrderStatus,
    val routeId: String,
    val seats: List<String>,
    val totalAmount: BigDecimal,
    val createdAt: LocalDateTime
)

/**
 * Result of an operator redrive. [orderStatus] is the durable phase the resumed pipeline will pick up
 * from, which is the only thing that decides what happens next.
 */
data class RedriveResponse(
    val orderId: UUID,
    val orderStatus: OrderStatus,
    val attemptCount: Int,
)

/** [evidenceReference] is the incident or audit reference; resolving without one is rejected. */
data class ResolveReconciliationRequest(
    val evidenceReference: String,
)

data class ResolveReconciliationResponse(
    val orderId: UUID,
    val recordId: UUID,
    val kind: String,
    val status: String,
    val resolvedAt: OffsetDateTime,
)
