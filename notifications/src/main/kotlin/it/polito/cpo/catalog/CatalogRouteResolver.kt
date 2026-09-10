package it.polito.cpo.catalog

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a route id into the trip context a notification displays, backed by a cache.
 *
 * **Why a cache and not a call per event.** Catalog is, by its own contract, read-heavy and
 * tolerant of stale data, and it currently serves six routes that change on the scale of a
 * timetable rather than a checkout. Caching turns the steady state into a map lookup with no I/O
 * at all, which matters because the Kafka listener runs on the consumer thread: a per-event call
 * would put a slow Catalog directly in front of consumer lag. The accepted cost is that a renamed
 * station stays stale for the life of the pod — a display detail on a best-effort service, weighed
 * against a dependency in the consume path.
 *
 * **Why no circuit breaker.** The money-path clients in Orders carry one because they call on every
 * request; here a miss happens a handful of times per pod and is bounded by [TIMEOUT]. Adding
 * resilience4j to this service to guard a call that almost never happens would be ceremony, not
 * protection. Failure degrades instead: [resolve] returns null and the notification is delivered
 * without trip context. Notifications is killable by design, and it stays that way when its own
 * dependency is the thing that is down.
 *
 * Failures are deliberately not cached. A negative entry would outlive a Catalog restart and turn a
 * transient blip into permanently context-free notifications; a retry on the next event is cheap.
 */
@Component
class CatalogRouteResolver(
    @Value("\${eurotransit.services.catalog.url}") catalogUrl: String,
    webClientBuilder: WebClient.Builder,
) {
    private val log = LoggerFactory.getLogger(CatalogRouteResolver::class.java)

    // The shared builder carries the correlation-id customizer from :observability, so the outbound
    // call is grep-able by the same correlation id as the checkout that caused the notification.
    // No bearer: GET /api/catalog/** is public by contract, which is why this needs no service
    // credential and cannot leak one.
    private val webClient = webClientBuilder.baseUrl(catalogUrl).build()

    private val cache = ConcurrentHashMap<String, RouteSummary>()

    fun resolve(routeId: String): RouteSummary? {
        cache[routeId]?.let { return it }

        // Blocking, on the Kafka consumer thread, on purpose. The alternative — a suspending
        // listener or a separate dispatcher — buys nothing when this runs a handful of times per
        // pod, and it is bounded by the timeout below. The hot path never reaches this line.
        val resolved = try {
            webClient.get()
                .uri("/api/catalog/routes/{routeId}", routeId)
                .retrieve()
                .bodyToMono(RouteSummary::class.java)
                .timeout(TIMEOUT)
                .block(TIMEOUT.plusMillis(200))
        } catch (ex: Exception) {
            // Never propagates: a notification without trip context is worth more to the customer
            // than no notification, and the listener must not lose the event over a display detail.
            log.warn("Could not resolve route {} from Catalog; delivering without trip context", routeId, ex)
            null
        }

        return resolved?.also {
            if (cache.size < MAX_CACHED_ROUTES) cache[routeId] = it
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(1)

        // A bound, not a capacity plan: the catalogue is far smaller than this. It exists so a
        // pathological stream of unknown route ids cannot grow the map without limit. Past the
        // bound the resolver keeps working, it just stops caching.
        const val MAX_CACHED_ROUTES = 500
    }
}
