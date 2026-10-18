package it.polito.cpo.client

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import it.polito.cpo.contracts.inventory.SeatAvailabilityResponse
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDate

/**
 * The seat-map read, deliberately kept out of [WebClientInventoryClient].
 *
 * That class binds `inventory`'s breaker, retry and bulkhead as fields and applies them to every
 * call. This one binds the `inventory-read` instances instead and composes **no retry at all** —
 * see [it.polito.cpo.config.ResilienceConfiguration], where the absent registration is the
 * decision. Keeping the two in separate classes is what makes "browsing traffic cannot open the
 * money path's breaker" a structural property instead of a convention someone has to remember.
 */
@Component
@Profile("!dev")
class WebClientInventoryReadClient(
    @Value("\${eurotransit.services.inventory.url}") private val inventoryUrl: String,
    webClientBuilder: WebClient.Builder,
    serviceAuthExchangeFilter: ExchangeFilterFunction,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    bulkheadRegistry: BulkheadRegistry,
    @Value("\${eurotransit.services.inventory.read-timeout-ms:5000}") timeoutMillis: Long = 5000,
) : InventoryReadClient {

    // Same builder as the money-path client: it carries the correlation-id customizer and client
    // metrics. Only the ROLE_service bearer is added, because /api/inventory/** requires it — the
    // seat map is an internal endpoint reached with a service credential, which is exactly why
    // Inventory stays unreachable from a browser while this façade is public.
    private val webClient = webClientBuilder
        .baseUrl(inventoryUrl)
        .filter(serviceAuthExchangeFilter)
        .build()

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory-read")
    private val bulkhead = bulkheadRegistry.bulkhead("inventory-read")

    // Includes first-use service-token acquisition and connection setup, not only Inventory work.
    // Keep the wait bounded while allowing cold requests more time than the former one-second limit.
    private val timeout = Duration.ofMillis(timeoutMillis)

    override suspend fun getSeatAvailability(routeId: String, travelDate: LocalDate): SeatAvailabilityOutcome =
        try {
            val response = webClient.get()
                .uri { builder ->
                    builder.path("/api/inventory/availability")
                        .queryParam("routeId", routeId)
                        .queryParam("travelDate", travelDate)
                        .build()
                }
                .retrieve()
                .onStatus({ it.is4xxClientError }, ClientResponse::asDefinitiveRefusal)
                .bodyToMono(SeatAvailabilityResponse::class.java)
                .timeout(timeout)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                // No RetryOperator here, and not by omission.
                .awaitSingle()

            SeatAvailabilityOutcome.Found(
                response.seats.map { SeatAvailability(seatId = it.seatId, available = it.isAvailable) },
            )
        } catch (refusal: DefinitiveRefusal) {
            // The only refusal this endpoint has. Anything else 4xx is a contract or credential
            // problem rather than an answer, and rethrowing keeps it an unknown outcome instead of
            // reporting "no seats" to a customer.
            if (refusal.code == ROUTE_NOT_FOUND) SeatAvailabilityOutcome.NotFound else throw refusal
        }

    private companion object {
        const val ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND"
    }
}
