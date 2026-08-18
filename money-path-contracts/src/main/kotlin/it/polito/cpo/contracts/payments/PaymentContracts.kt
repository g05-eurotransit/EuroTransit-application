package it.polito.cpo.contracts.payments

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

// orders -> payments : POST /api/payments/authorize (Idempotency-Key header)
data class PaymentRequest(
    val orderId: UUID,
    val principalId: String,
    val amount: BigDecimal,
    val currency: String,
    val paymentMethodToken: String
)

enum class PaymentStatus { AUTHORIZED, DECLINED, CONFLICT, DEPENDENCY_FAILED }

data class PaymentResponse(
    val status: PaymentStatus,
    val providerReference: String?,
    val errorCode: String? = null
)

// orders -> payments : POST /api/payments/refund (Idempotency-Key header).
// The refund keys on orderId only; payments resolves the provider reference from its own
// authorization record, so orders never has to store/pass a provider reference.
data class PaymentRefundRequest(
    val orderId: String,
    val amount: BigDecimal? = null // null = full refund
)

enum class RefundStatus { REFUNDED, FAILED, CONFLICT, DEPENDENCY_FAILED, NOT_FOUND }

data class PaymentRefundResponse(
    val status: RefundStatus,
    val refundReference: String?,
    val errorCode: String? = null
)
