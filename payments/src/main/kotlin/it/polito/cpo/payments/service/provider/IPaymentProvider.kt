package it.polito.cpo.payments.service.provider

import java.math.BigDecimal

interface IPaymentProvider {
    suspend fun authorize(
        amount: BigDecimal,
        currency: String,
        paymentMethodToken: String,
        idempotencyKey: String
    ): ProviderAuthorizationResult
    suspend fun refund(
        providerReference: String,
        amount: BigDecimal?, // null means full refund
        idempotencyKey: String
    ): ProviderRefundResult
}

data class ProviderAuthorizationResult(
    val success: Boolean,
    val providerReference: String?,
    val errorCode: String? = null
)

data class ProviderRefundResult(
    val success: Boolean,
    val refundReference: String?,
    val errorCode: String? = null
)
