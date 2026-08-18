package it.polito.cpo.payments.service.payment

import tools.jackson.databind.ObjectMapper
import it.polito.cpo.payments.service.provider.IPaymentProvider
import it.polito.cpo.contracts.payments.PaymentResponse
import it.polito.cpo.contracts.payments.PaymentStatus
import it.polito.cpo.contracts.events.PaymentAuthorizationEvent
import it.polito.cpo.contracts.events.PaymentAuthorizationPayload
import it.polito.cpo.contracts.events.PaymentRefundEvent
import it.polito.cpo.contracts.events.PaymentRefundPayload
import it.polito.cpo.observability.ApiException
import it.polito.cpo.observability.CorrelationId
import it.polito.cpo.payments.model.IdempotencyRecord
import it.polito.cpo.payments.model.PaymentAuthorization
import it.polito.cpo.payments.model.PaymentRefund
import it.polito.cpo.payments.repository.IdempotencyRepository
import it.polito.cpo.payments.repository.PaymentAuthorizationRepository
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Service
class PaymentService(
    private val providerAdapter: IPaymentProvider,
    private val idempotencyRepository: IdempotencyRepository,
    private val paymentAuthorizationRepository: PaymentAuthorizationRepository,
    private val paymentRefundRepository: it.polito.cpo.payments.repository.PaymentRefundRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    transactionManager: ReactiveTransactionManager
) : IPaymentService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txOperator = TransactionalOperator.create(transactionManager)

    companion object {
        private val ISO_CURRENCY_PATTERN = Regex("^[A-Z]{3}$")
    }

    override suspend fun authorize(
        orderId: String,
        principalId: String,
        amount: BigDecimal,
        currency: String,
        paymentMethodToken: String,
        idempotencyKey: String
    ): PaymentResponse {

        // Validate before touching the DB or the provider: a malformed amount/currency is a caller
        // (bad-request) error, NOT a payment decline — surface it as 400, not a DECLINED/422 result,
        // so a client bug is not mistaken for a real bank decline (api-design.md error model).
        if (amount <= BigDecimal.ZERO) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Amount must be strictly positive")
        }
        if (!ISO_CURRENCY_PATTERN.matches(currency)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY", "Currency must be a 3-letter ISO 4217 code")
        }

        val fingerprint = "$orderId-$amount-$currency-$paymentMethodToken"

        // 1. Check idempotency record
        val existingRecord = idempotencyRepository.findById(idempotencyKey)
        if (existingRecord != null) {
            if (existingRecord.requestFingerprint != fingerprint || existingRecord.principalId != principalId) {
                log.warn("Idempotency conflict for key $idempotencyKey")
                return PaymentResponse(PaymentStatus.CONFLICT, null, "IDEMPOTENCY_CONFLICT")
            }
            return objectMapper.readValue(existingRecord.responseBody, PaymentResponse::class.java)
        }

        // 2. Call provider (outside any DB transaction)
        val result = try {
            providerAdapter.authorize(amount, currency, paymentMethodToken, idempotencyKey)
        } catch (e: Exception) {
            log.error("Payment provider failed", e)
            return PaymentResponse(PaymentStatus.DEPENDENCY_FAILED, null, "PROVIDER_UNAVAILABLE")
        }

        val authStatus = if (result.success) PaymentStatus.AUTHORIZED else PaymentStatus.DECLINED

        val authorization = PaymentAuthorization(
            orderId = orderId,
            principalId = principalId,
            amount = amount,
            currency = currency,
            status = authStatus.name,
            providerReference = result.providerReference,
            idempotencyKey = idempotencyKey
        ).apply { setAsNew(true) }

        val response = PaymentResponse(authStatus, result.providerReference, result.errorCode)
        val idempotencyRecord = IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            operation = "authorize",
            principalId = principalId,
            requestFingerprint = fingerprint,
            responseCode = if (result.success) 200 else 422,
            responseBody = objectMapper.writeValueAsString(response),
            createdAt = LocalDateTime.now()
        ).apply { setAsNew(true) }

        // 3+4. Persist idempotency record and authorization atomically, in a short transaction.
        // Order matters: payment_authorizations.idempotency_key has a FK to
        // idempotency_records(idempotency_key), so the idempotency record must be inserted first.
        txOperator.executeAndAwait {
            idempotencyRepository.save(idempotencyRecord)
            paymentAuthorizationRepository.save(authorization)
        }

        // 5. Emit event
        val eventType = if (result.success) "payment-authorized" else "payment-declined"
        publishAuthorizationEvent(eventType, orderId, principalId, authorization)

        return response
    }

    override suspend fun refund(
        orderId: String,
        amount: BigDecimal?,
        idempotencyKey: String
    ): it.polito.cpo.payments.dto.PaymentRefundResponse {

        // null amount means full refund; an explicit amount must be strictly positive. A malformed
        // amount is a caller (bad-request) error, not a business refund failure → surface it as 400.
        if (amount != null && amount <= BigDecimal.ZERO) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Refund amount must be strictly positive when provided")
        }

        val fingerprint = "refund-$orderId-$amount"

        // 1. Check idempotency record
        val existingRecord = idempotencyRepository.findById(idempotencyKey)
        if (existingRecord != null) {
            if (existingRecord.requestFingerprint != fingerprint) {
                log.warn("Idempotency conflict for key $idempotencyKey")
                return it.polito.cpo.payments.dto.PaymentRefundResponse(IPaymentService.RefundStatus.CONFLICT, null, "IDEMPOTENCY_CONFLICT")
            }
            return objectMapper.readValue(existingRecord.responseBody, it.polito.cpo.payments.dto.PaymentRefundResponse::class.java)
        }

        // 2. Fetch original authorization
        val authorization = paymentAuthorizationRepository.findByOrderId(orderId)
            ?: return it.polito.cpo.payments.dto.PaymentRefundResponse(IPaymentService.RefundStatus.NOT_FOUND, null, "AUTHORIZATION_NOT_FOUND")

        if (authorization.status != PaymentAuthorization.STATUS_AUTHORIZED || authorization.providerReference == null) {
            return it.polito.cpo.payments.dto.PaymentRefundResponse(IPaymentService.RefundStatus.FAILED, null, "INVALID_AUTHORIZATION_STATUS")
        }

        // 3. Call provider (outside any DB transaction)
        val result = try {
            providerAdapter.refund(authorization.providerReference, amount, idempotencyKey)
        } catch (e: Exception) {
            log.error("Payment provider failed on refund", e)
            return it.polito.cpo.payments.dto.PaymentRefundResponse(IPaymentService.RefundStatus.DEPENDENCY_FAILED, null, "PROVIDER_UNAVAILABLE")
        }

        val refundStatus = if (result.success) IPaymentService.RefundStatus.REFUNDED else IPaymentService.RefundStatus.FAILED

        val paymentRefund = it.polito.cpo.payments.model.PaymentRefund(
            authorizationId = authorization.getId(), // We must use getId() since id is private
            amount = amount,
            currency = authorization.currency,
            status = refundStatus.name,
            providerReference = result.refundReference,
            idempotencyKey = idempotencyKey
        ).apply { setAsNew(true) }

        val response = it.polito.cpo.payments.dto.PaymentRefundResponse(refundStatus, result.refundReference, result.errorCode)
        val idempotencyRecord = IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            operation = "refund",
            principalId = authorization.principalId,
            requestFingerprint = fingerprint,
            responseCode = if (result.success) 200 else 422,
            responseBody = objectMapper.writeValueAsString(response),
            createdAt = LocalDateTime.now()
        ).apply { setAsNew(true) }

        // 4+5. Persist idempotency record and refund atomically, in a short transaction.
        // Order matters: payment_refunds.idempotency_key has a FK to
        // idempotency_records(idempotency_key), so the idempotency record must be inserted first.
        txOperator.executeAndAwait {
            idempotencyRepository.save(idempotencyRecord)
            paymentRefundRepository.save(paymentRefund)
        }

        // 6. Emit event
        val eventType = if (result.success) "payment-refunded" else "payment-refund-failed"
        publishRefundEvent(eventType, orderId, authorization.principalId, paymentRefund)

        return response
    }

    // Events are typed contracts (money-path-contracts) pre-serialized to a JSON String with the app's
    // Jackson 3 ObjectMapper, then sent through the StringSerializer producer — symmetric with the
    // String-based consumers (orders/notifications) and aligned with ADR-010 (shared envelope, no drift).
    private fun publishAuthorizationEvent(
        eventType: String,
        orderId: String,
        principalId: String,
        authorization: PaymentAuthorization
    ) {
        val event = PaymentAuthorizationEvent(
            eventType = eventType,
            correlationId = MDC.get(CorrelationId.CONTEXT_KEY) ?: "",
            orderId = orderId,
            principalId = principalId,
            payload = PaymentAuthorizationPayload(
                status = authorization.status,
                providerReference = authorization.providerReference,
                amount = authorization.amount,
                currency = authorization.currency,
                errorCode = authorization.errorCode
            )
        )
        kafkaTemplate.send(eventType, orderId, objectMapper.writeValueAsString(event))
    }

    private fun publishRefundEvent(
        eventType: String,
        orderId: String,
        principalId: String,
        refund: PaymentRefund
    ) {
        val event = PaymentRefundEvent(
            eventType = eventType,
            correlationId = MDC.get(CorrelationId.CONTEXT_KEY) ?: "",
            orderId = orderId,
            principalId = principalId,
            payload = PaymentRefundPayload(
                status = refund.status,
                refundReference = refund.providerReference,
                amount = refund.amount,
                currency = refund.currency
            )
        )
        kafkaTemplate.send(eventType, orderId, objectMapper.writeValueAsString(event))
    }
}
