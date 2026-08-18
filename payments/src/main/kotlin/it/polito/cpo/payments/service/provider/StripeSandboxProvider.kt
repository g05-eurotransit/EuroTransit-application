package it.polito.cpo.payments.service.provider

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.RetryRegistry
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.math.BigDecimal

@Component
class StripeSandboxProvider(
    @Value("\${payments.provider.sandbox-url}") private val sandboxUrl: String,
    @Value("\${payments.provider.secret-key}") private val secretKey: String,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    retryRegistry: RetryRegistry,
    bulkheadRegistry: BulkheadRegistry,
) : IPaymentProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    // Connection-level timeouts: 5s to establish the TCP connection, 10s for the full response.
    private val httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
        .responseTimeout(Duration.ofSeconds(10))

    // WebClient agganciato alla chiave segreta e ai timeout.
    private val webClient = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(httpClient))
        .baseUrl(sandboxUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $secretKey")
        .build()

    // Resilience4j instances for the outbound provider call (see ResilienceConfiguration).
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("stripe")
    private val retry = retryRegistry.retry("stripe")
    private val bulkhead = bulkheadRegistry.bulkhead("stripe")

    override suspend fun authorize(
        amount: BigDecimal,
        currency: String,
        paymentMethodToken: String,
        idempotencyKey: String
    ): ProviderAuthorizationResult {
        log.info("Sending charge request to Stripe Sandbox for amount $amount $currency")
        
        val formData = LinkedMultiValueMap<String, String>()
        // Stripe expects amount in cents
        formData.add("amount", amount.multiply(BigDecimal(100)).toLong().toString())
        formData.add("currency", currency.lowercase())
        formData.add("source", paymentMethodToken)
        // capture defaults to true: authorize and charge in one call

        return webClient.post()
            .uri("/charges")
            .header("Idempotency-Key", "stripe_$idempotencyKey")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .bodyToMono(StripeChargeResponse::class.java)
            .map { ProviderAuthorizationResult(success = true, providerReference = it.id) }
            // A card decline (HTTP 402) / other 4xx is a business outcome, NOT a dependency failure:
            // recover it to a value BEFORE the resilience operators so it never trips the breaker nor
            // triggers a retry. 5xx / transport faults fall through to retry + circuit breaker.
            // Never log the raw provider response body (api-design.md forbids raw payment/card data):
            // log only the whitelisted error code.
            .onErrorResume(WebClientResponseException::class.java) { e ->
                val errorBody = try { e.getResponseBodyAs(StripeErrorResponse::class.java) } catch (_: Exception) { null }
                log.warn("Stripe API returned error: status={} code={}", e.statusCode, errorBody?.error?.code ?: "unknown")
                if (e.statusCode.value() == 402) {
                    // HTTP 402 Payment Required = Stripe card decline (e.g. insufficient funds): a business
                    // outcome, recovered to a value so it neither trips the breaker nor gets retried.
                    Mono.just(
                        ProviderAuthorizationResult(
                            success = false,
                            providerReference = null,
                            errorCode = errorBody?.error?.code ?: "card_declined"
                        )
                    )
                } else {
                    // Any other status (401 bad key, 400, 429, 5xx) is NOT a card decline: propagate so it
                    // reaches retry + circuit breaker and surfaces as DEPENDENCY_FAILED, not a false decline.
                    Mono.error(e)
                }
            }
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(RetryOperator.of(retry))
            .awaitSingle()
    }

    override suspend fun refund(
        providerReference: String,
        amount: BigDecimal?,
        idempotencyKey: String
    ): ProviderRefundResult {
        log.info("Sending refund request to Stripe Sandbox for charge $providerReference")
        
        val formData = LinkedMultiValueMap<String, String>()
        formData.add("charge", providerReference)
        if (amount != null) {
            formData.add("amount", amount.multiply(BigDecimal(100)).toLong().toString())
        }

        return webClient.post()
            .uri("/refunds")
            .header("Idempotency-Key", "stripe_refund_$idempotencyKey")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .bodyToMono(StripeRefundResponse::class.java)
            .map { ProviderRefundResult(success = true, refundReference = it.id) }
            // A 4xx from the provider is a business failure (recovered to a failed result); 5xx and
            // transport faults fall through to retry + circuit breaker. Log only the whitelisted code.
            .onErrorResume(WebClientResponseException::class.java) { e ->
                val errorBody = try { e.getResponseBodyAs(StripeErrorResponse::class.java) } catch (_: Exception) { null }
                log.warn("Stripe API returned error on refund: status={} code={}", e.statusCode, errorBody?.error?.code ?: "unknown")
                if (e.statusCode.is4xxClientError) {
                    Mono.just(
                        ProviderRefundResult(
                            success = false,
                            refundReference = null,
                            errorCode = errorBody?.error?.code ?: "refund_failed"
                        )
                    )
                } else {
                    Mono.error(e)
                }
            }
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(RetryOperator.of(retry))
            .awaitSingle()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StripeRefundResponse(
    val id: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StripeChargeResponse(
    val id: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StripeErrorResponse(
    val error: StripeErrorDetails?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StripeErrorDetails(
    val code: String?,
    val message: String?
)
