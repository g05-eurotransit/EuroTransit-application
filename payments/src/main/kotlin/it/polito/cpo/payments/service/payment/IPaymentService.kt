package it.polito.cpo.payments.service.payment

import it.polito.cpo.contracts.payments.PaymentResponse
import it.polito.cpo.payments.dto.PaymentRefundResponse
import java.math.BigDecimal

interface IPaymentService {
    enum class RefundStatus {
        REFUNDED, FAILED, CONFLICT, DEPENDENCY_FAILED, NOT_FOUND
    }

    suspend fun authorize(
        orderId: String,
        principalId: String,
        amount: BigDecimal,
        currency: String,
        paymentMethodToken: String,
        idempotencyKey: String
    ): PaymentResponse

    suspend fun refund(
        orderId: String,
        amount: BigDecimal?,
        idempotencyKey: String
    ): PaymentRefundResponse
}
