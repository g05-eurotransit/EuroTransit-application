package it.polito.cpo.payments.controllers

import it.polito.cpo.payments.service.payment.IPaymentService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.contracts.payments.PaymentResponse
import it.polito.cpo.contracts.payments.PaymentStatus
import java.math.BigDecimal

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentService: IPaymentService
) {

    @PostMapping("/authorize")
    suspend fun authorizePayment(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("Authorization", required = false) authorizationHeader: String?,
        @RequestBody request: PaymentRequest
    ): ResponseEntity<PaymentResponse> {
        
        val response = paymentService.authorize(
            orderId = request.orderId.toString(),
            principalId = request.principalId,
            amount = request.amount,
            currency = request.currency,
            paymentMethodToken = request.paymentMethodToken,
            idempotencyKey = idempotencyKey
        )

        val httpStatus = when (response.status) {
            PaymentStatus.AUTHORIZED -> HttpStatus.OK
            PaymentStatus.DECLINED -> HttpStatus.UNPROCESSABLE_ENTITY
            PaymentStatus.CONFLICT -> HttpStatus.CONFLICT
            PaymentStatus.DEPENDENCY_FAILED -> HttpStatus.SERVICE_UNAVAILABLE
        }
        return ResponseEntity.status(httpStatus).body(response)
    }

    @PostMapping("/refund")
    suspend fun refundPayment(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("Authorization", required = false) authorizationHeader: String?,
        @RequestBody request: it.polito.cpo.payments.dto.PaymentRefundRequest
    ): ResponseEntity<it.polito.cpo.payments.dto.PaymentRefundResponse> {
        
        val result = paymentService.refund(
            orderId = request.orderId,
            amount = request.amount,
            idempotencyKey = idempotencyKey
        )

        return when (result.status) {
            IPaymentService.RefundStatus.REFUNDED -> ResponseEntity.ok(result)
            IPaymentService.RefundStatus.FAILED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result)
            IPaymentService.RefundStatus.CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(result)
            IPaymentService.RefundStatus.NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(result)
            IPaymentService.RefundStatus.DEPENDENCY_FAILED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result)
        }
    }
}
