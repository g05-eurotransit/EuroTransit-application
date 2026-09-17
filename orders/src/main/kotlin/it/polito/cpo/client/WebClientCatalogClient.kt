package it.polito.cpo.client

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import it.polito.cpo.contracts.catalog.OfferDetail
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Catalog offer lookup, on its own `catalog-read` breaker and bulkhead and with no retry.
 *
 * Same structure and same reasoning as [WebClientInventoryReadClient]: a separate class is what makes
 * "Catalog cannot open the money path's breaker" a property of the code rather than a rule someone
 * has to remember. See [it.polito.cpo.config.ResilienceConfiguration], where the absent retry
 * registration is the decision.
 *
 * **No service credential, unlike every other client here.** Every `GET` under `/api/catalog` is
 * `permitAll` in
 * Catalog's own `SecurityConfig` — catalog data is public and staleness-tolerant — so there is no
 * `ExchangeFilterFunction` to attach and no client-credentials token to fetch. If that ever changes,
 * the calls fail loudly as a `401`/`403` that no branch below converts into an answer.
 */
@Component
@Profile("!dev")
class WebClientCatalogClient(
    @Value("\${eurotransit.services.catalog.url}") private val catalogUrl: String,
    webClientBuilder: WebClient.Builder,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    bulkheadRegistry: BulkheadRegistry,
) : CatalogClient {

    // The shared builder, for the correlation-id customizer and the client metrics it carries.
    private val webClient = webClientBuilder
        .baseUrl(catalogUrl)
        .build()

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("catalog-read")
    private val bulkhead = bulkheadRegistry.bulkhead("catalog-read")

    // Tighter than the money path's two seconds, and for a stronger reason than the seat map's: this
    // call sits in front of the synchronous 202, so every millisecond spent here is one the customer
    // waits before their checkout is even accepted. Catalog answers from an in-memory list.
    private val timeout = Duration.ofSeconds(1)

    override suspend fun getOffer(offerId: String): OfferOutcome =
        try {
            val detail = webClient.get()
                .uri("/api/catalog/offers/{offerId}", offerId)
                .retrieve()
                .onStatus({ it.is4xxClientError }, ClientResponse::asDefinitiveRefusal)
                .bodyToMono(OfferDetail::class.java)
                .timeout(timeout)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                // No RetryOperator, and not by omission — see ResilienceConfiguration.
                .awaitSingle()

            OfferOutcome.Found(
                ResolvedOffer(
                    offerId = detail.offerId,
                    routeId = detail.routeId,
                    price = detail.price,
                    currency = detail.currency,
                    refundPolicy = detail.refundPolicy,
                    termsVersion = detail.termsVersion,
                    departureTime = detail.departureTime,
                    departureZone = detail.departureZone,
                ),
            )
        } catch (refusal: DefinitiveRefusal) {
            // The only refusal this endpoint has. Anything else 4xx is a contract or credential
            // problem rather than an answer about the offer, and rethrowing keeps it an unknown
            // outcome — the checkout then fails closed instead of rejecting the customer's offer id
            // as if Catalog had denied it.
            //
            // Catalog's own `500 OFFER_ROUTE_MISSING` — an offer pointing at a route that does not
            // exist — never reaches this branch either: a 5xx is not decoded here at all, so a
            // broken catalog stops checkouts rather than pricing them against a missing departure.
            if (refusal.code == OFFER_NOT_FOUND) OfferOutcome.NotFound else throw refusal
        }

    override suspend fun getRoute(routeId: String): RouteOutcome =
        try {
            val wire = webClient.get()
                .uri("/api/catalog/routes/{routeId}", routeId)
                .retrieve()
                .onStatus({ it.is4xxClientError }, ClientResponse::asDefinitiveRefusal)
                .bodyToMono(RouteWire::class.java)
                .timeout(timeout)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .awaitSingle()

            RouteOutcome.Found(
                RouteSchedule(
                    routeId = wire.routeId,
                    departureTime = wire.departureTime,
                    departureZone = wire.departureZone,
                ),
            )
        } catch (refusal: DefinitiveRefusal) {
            // Same rule as getOffer: the one refusal this endpoint has is decoded, everything else
            // 4xx stays an unknown outcome rather than becoming an answer about the route.
            if (refusal.code == ROUTE_NOT_FOUND) RouteOutcome.NotFound else throw refusal
        }

    /**
     * The three fields Orders reads off `GET /api/catalog/routes/{routeId}`.
     *
     * A local wire type because Catalog's `Route` is not a published contract — it lives in
     * `catalog/`, and only `OfferDetail` was promoted to `money-path-contracts`. Promoting `Route`
     * too would be a cross-slice contract change for three fields, so this maps what it needs and
     * lets Jackson ignore the rest.
     */
    private data class RouteWire(
        val routeId: String,
        val departureTime: LocalTime,
        val departureZone: ZoneId,
    )

    private companion object {
        const val OFFER_NOT_FOUND = "OFFER_NOT_FOUND"
        const val ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND"
    }
}
