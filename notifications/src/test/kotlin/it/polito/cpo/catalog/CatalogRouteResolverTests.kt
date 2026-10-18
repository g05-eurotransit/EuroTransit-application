package it.polito.cpo.catalog

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drives the resolver through a stubbed exchange, so the two properties that matter — the steady
 * state does no I/O, and a Catalog outage degrades instead of failing — are asserted directly
 * rather than inferred from a running service.
 */
class CatalogRouteResolverTests {

    private val routeJson = """
        {
          "routeId": "rt-to-mi-001",
          "origin": "Torino Porta Nuova",
          "destination": "Milano Centrale",
          "departureTime": "07:00:00",
          "arrivalTime": "08:05:00",
          "trainType": "Frecciarossa",
          "operatingDays": ["MON"]
        }
    """.trimIndent()

    private fun resolverOver(exchange: ExchangeFunction, calls: AtomicInteger): CatalogRouteResolver =
        CatalogRouteResolver(
            catalogUrl = "http://catalog.invalid",
            webClientBuilder = WebClient.builder().exchangeFunction { request ->
                calls.incrementAndGet()
                exchange.exchange(request)
            },
        )

    @Test
    fun `resolves a route and ignores fields it does not need`() {
        val calls = AtomicInteger()
        val resolver = resolverOver(okWith(routeJson), calls)

        val route = resolver.resolve("rt-to-mi-001")

        // The Catalog response carries arrivalTime, trainType and operatingDays; binding to a
        // narrow local type means a Catalog field change cannot break this service.
        assertEquals("Torino Porta Nuova", route?.origin)
        assertEquals("Milano Centrale", route?.destination)
        assertEquals(LocalTime.of(7, 0), route?.departureTime)
    }

    @Test
    fun `a second lookup of the same route does no I-O`() {
        val calls = AtomicInteger()
        val resolver = resolverOver(okWith(routeJson), calls)

        resolver.resolve("rt-to-mi-001")
        resolver.resolve("rt-to-mi-001")

        // The reason this runs on the Kafka consumer thread at all: past the first miss there is
        // no call to be slow.
        assertEquals(1, calls.get())
    }

    @Test
    fun `an unreachable Catalog yields no route instead of an error`() {
        val calls = AtomicInteger()
        val resolver = resolverOver({ Mono.error(IllegalStateException("connection refused")) }, calls)

        assertNull(resolver.resolve("rt-to-mi-001"))
    }

    @Test
    fun `a failure is not cached`() {
        val calls = AtomicInteger()
        val resolver = resolverOver({ Mono.error(IllegalStateException("connection refused")) }, calls)

        resolver.resolve("rt-to-mi-001")
        resolver.resolve("rt-to-mi-001")

        // A negative entry would outlive the outage and make the degradation permanent.
        assertEquals(2, calls.get())
    }

    private fun okWith(body: String) = ExchangeFunction { _ ->
        Mono.just(
            ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build(),
        )
    }
}
