package it.polito.cpo.client

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.RetryRegistry
import it.polito.cpo.contracts.payments.PaymentRefundRequest
import it.polito.cpo.contracts.payments.PaymentRefundResponse
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.contracts.payments.PaymentResponse
import it.polito.cpo.contracts.payments.PaymentStatus
import it.polito.cpo.contracts.payments.RefundStatus
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.UUID

@Component
@Profile("!dev")
class WebClientPaymentClient(
    @Value("\${eurotransit.services.payments.url}") private val paymentsUrl: String,
    webClientBuilder: WebClient.Builder,
    serviceAuthExchangeFilter: ExchangeFilterFunction,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    retryRegistry: RetryRegistry,
    bulkheadRegistry: BulkheadRegistry,
) : PaymentClient {

    // Use the auto-configured builder: it already applies the CorrelationWebClientCustomizer
    // (X-Correlation-Id + X-Service-Name) and client-side metrics/tracing. We add only the
    // ROLE_service Bearer filter (client-credentials, for /api/payments/** security).
    private val webClient = webClientBuilder
        .baseUrl(paymentsUrl)
        .filter(serviceAuthExchangeFilter)
        .build()

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("payments")
    private val retry = retryRegistry.retry("payments")
    private val bulkhead = bulkheadRegistry.bulkhead("payments")
    // Payments integrates with an external provider: allow a slightly longer timeout.
    private val timeout = Duration.ofSeconds(3)

    override suspend fun authorizePayment(
        request: PaymentRequest,
        idempotencyKey: String,
        correlationId: String,
    ): AuthorizationOutcome =
        try {
            val response = webClient.post()
                .uri("/api/payments/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                // Set explicitly: this correlation id comes from the order-placed event being
                // processed, which is the durable identity of this checkout attempt.
                .header("X-Correlation-Id", correlationId)
                .bodyValue(request)
                .retrieve()
                .onStatus({ it.is4xxClientError }, ClientResponse::asDefinitiveRefusal)
                .bodyToMono(PaymentResponse::class.java)
                .timeout(timeout)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .awaitSingle()
            authorization(response)
        } catch (refusal: DefinitiveRefusal) {
            // Only an outright decline is definitive. IDEMPOTENCY_CONFLICT means the key was reused
            // with a different payload, so the authorization state is unknown and treating it as a
            // decline could discard a real authorization: rethrow and let redelivery resolve it.
            if (refusal.code == PaymentStatus.DECLINED.name) {
                AuthorizationOutcome.Declined(refusal.detail)
            } else {
                throw refusal
            }
        }

    override suspend fun refund(orderId: UUID, idempotencyKey: String): ReversalOutcome =
        try {
            val response = webClient.post()
                .uri("/api/payments/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                // Full refund: amount = null. Payments looks up the provider reference by orderId.
                .bodyValue(PaymentRefundRequest(orderId = orderId.toString(), amount = null))
                .retrieve()
                .onStatus({ it.is4xxClientError }, ClientResponse::asDefinitiveRefusal)
                .bodyToMono(PaymentRefundResponse::class.java)
                .timeout(timeout)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .awaitSingle()
            reversal(response)
        } catch (refusal: DefinitiveRefusal) {
            // Every 4xx here is definitive, including NOT_FOUND: Orders only ever reverses after a
            // definitive AUTHORIZED, so a missing authorization is a genuine cross-service
            // inconsistency for reconciliation — not a licence to call the money resolved.
            // IDEMPOTENCY_CONFLICT is classified definitive on purpose: retrying the same
            // refund-{orderId} key would conflict forever, so parking with an alert beats an
            // endless dead-letter loop.
            ReversalOutcome.Failed(refusal.code, refusal.detail)
        }

    /**
     * A 200 carrying anything other than AUTHORIZED or DECLINED means the authorization result is
     * not a decision Orders can act on (for example DEPENDENCY_FAILED surfacing as 200 after a
     * contract change), so it is an unknown outcome and must be retried, not compensated.
     */
    private fun authorization(response: PaymentResponse): AuthorizationOutcome = when (response.status) {
        PaymentStatus.AUTHORIZED -> AuthorizationOutcome.Authorized(response.providerReference)
        PaymentStatus.DECLINED -> AuthorizationOutcome.Declined(response.errorCode)
        else -> error("Payments answered 200 with status ${response.status}, which is not a decision")
    }

    private fun reversal(response: PaymentRefundResponse): ReversalOutcome = when (response.status) {
        RefundStatus.REFUNDED -> ReversalOutcome.Reversed(response.refundReference)
        RefundStatus.DEPENDENCY_FAILED -> error("Payments answered 200 with status ${response.status}")
        else -> ReversalOutcome.Failed(response.status.name, response.errorCode)
    }
}
