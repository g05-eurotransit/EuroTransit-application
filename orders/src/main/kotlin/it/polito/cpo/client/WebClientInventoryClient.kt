package it.polito.cpo.client

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.RetryRegistry
import it.polito.cpo.contracts.inventory.ReservationConfirmationRequest
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationResponse
import it.polito.cpo.contracts.inventory.ReservationStatus
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
class WebClientInventoryClient(
    @Value("\${eurotransit.services.inventory.url}") private val inventoryUrl: String,
    webClientBuilder: WebClient.Builder,
    serviceAuthExchangeFilter: ExchangeFilterFunction,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    retryRegistry: RetryRegistry,
    bulkheadRegistry: BulkheadRegistry,
) : InventoryClient {

    // Use the auto-configured builder: it already applies the CorrelationWebClientCustomizer
    // (X-Correlation-Id + X-Service-Name) and client-side metrics/tracing. We add only the
    // ROLE_service Bearer filter (client-credentials, for /api/inventory/** security).
    private val webClient = webClientBuilder
        .baseUrl(inventoryUrl)
        .filter(serviceAuthExchangeFilter)
        .build()

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory")
    private val retry = retryRegistry.retry("inventory")
    private val bulkhead = bulkheadRegistry.bulkhead("inventory")
    // Inventory is the consistency boundary: keep the timeout short.
    private val timeout = Duration.ofSeconds(2)

    override suspend fun reserveSeats(
        request: ReservationRequest,
        idempotencyKey: String,
        userId: String,
    ): HoldOutcome =
        try {
            val response = webClient.post()
                .uri("/api/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-User-Id", userId)
                .bodyValue(request)
                .retrieve()
                .onStatus({ it.is4xxClientError }, ClientResponse::asDefinitiveRefusal)
                .bodyToMono(ReservationResponse::class.java)
                .timeout(timeout)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .awaitSingle()
            HoldOutcome.Held(response.reservationId, response.expiresAt)
        } catch (refusal: DefinitiveRefusal) {
            HoldOutcome.Rejected(refusal.asKnownCodeOrRethrow(), refusal.detail)
        }

    override suspend fun confirmReservation(
        reservationId: String,
        orderId: UUID,
    ): ConfirmReservationOutcome =
        try {
            val response = webClient.put()
                // No Idempotency-Key and no X-User-Id: Inventory accepts neither on this endpoint.
                // X-Correlation-Id / X-Service-Name come from the builder's customizer, which reads
                // the Reactor context seeded by OrderPlacedEventListener — do not set them by hand.
                .uri("/api/inventory/reservations/{reservationId}/confirmation", reservationId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ReservationConfirmationRequest(orderId = orderId))
                .retrieve()
                // Business refusals are 4xx, so decode them here while the response is still open.
                // 5xx are deliberately left alone: they must keep surfacing as
                // WebClientResponseException so retry and the circuit breaker still see a dependency
                // failure, which is the correct classification for an unknown outcome.
                .onStatus({ it.is4xxClientError }, ClientResponse::asDefinitiveRefusal)
                .bodyToMono(ReservationResponse::class.java)
                .timeout(timeout)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .awaitSingle()
            confirmed(reservationId, response)
        } catch (refusal: DefinitiveRefusal) {
            ConfirmReservationOutcome.Refused(refusal.asKnownCodeOrRethrow(), refusal.detail)
        }

    override suspend fun releaseSeats(reservationId: String, idempotencyKey: String) {
        webClient.delete()
            .uri("/api/inventory/reservations/{reservationId}", reservationId)
            .header("Idempotency-Key", idempotencyKey)
            .retrieve()
            .bodyToMono(Void::class.java)
            .timeout(timeout)
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(RetryOperator.of(retry))
            .awaitSingleOrNull()
    }

    /**
     * A 200 that does not say CONFIRMED would mean Orders and Inventory disagree about what the
     * confirmation command does. That is not a business refusal, so it must not be compensated:
     * treat it as an unknown outcome and let redelivery and the DLT surface it.
     */
    private fun confirmed(reservationId: String, response: ReservationResponse): ConfirmReservationOutcome {
        check(response.status == ReservationStatus.CONFIRMED) {
            "Inventory answered 200 for reservation $reservationId with status ${response.status}, expected CONFIRMED"
        }
        return ConfirmReservationOutcome.Confirmed(response.reservationId)
    }
}

/**
 * Narrows a decoded refusal to a code Orders knows how to act on. An unrecognised 4xx code is
 * rethrown rather than treated as a refusal: Orders must not compensate on a result it cannot
 * interpret (invariant 8).
 */
private fun DefinitiveRefusal.asKnownCodeOrRethrow(): InventoryRefusalCode =
    InventoryRefusalCode.from(code) ?: throw this
