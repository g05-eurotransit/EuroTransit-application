package it.polito.cpo.payments.dto

import it.polito.cpo.payments.service.payment.IPaymentService
import java.math.BigDecimal



data class PaymentRefundRequest(
    val orderId: String,
    val amount: BigDecimal? = null // null means full refund
)

data class PaymentRefundResponse(
    val status: IPaymentService.RefundStatus,
    val refundReference: String?,
    val errorCode: String? = null
)
