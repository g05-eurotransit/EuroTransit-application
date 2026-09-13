package it.polito.cpo.client

import it.polito.cpo.config.ResilienceConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.HttpHandlerConnector
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Wire-level tests for the offer lookup, run in-process through [HttpHandlerConnector] like
 * [WebClientInventoryReadClientTest].
 *
 * Two things here are load-bearing and invisible in the client's own source. One is the absence of a
 * `RetryOperator`, asserted by `a failing offer lookup is attempted once, unlike the money path`. The
 * other is that **only** `OFFER_NOT_FOUND` becomes an answer: every other failure has to stay an
 * exception, because the checkout fails closed on an exception and rejects the customer's offer id
 * on an answer, and those are very different things to tell someone whose payment details are ready.
 */
class WebClientCatalogClientTest {

    private val offerJson = """
        {
          "offerId":"of-002",
          "routeId":"rt-to-mi-001",
          "fareClass":"BUSINESS",
          "price":49.90,
          "currency":"EUR",
          "conditions":"Refundable until 5 minutes before departure.",
          "refundPolicy":{"refundable":true,"cutoffMinutesBeforeDeparture":5},
          "termsVersion":1,
          "departureTime":"07:00:00",
          "departureZone":"Europe/Rome"
        }
    """.trimIndent()

    @Test
    fun `a 200 maps the wire offer onto the Orders-side offer`() = runBlocking {
        val client = catalogClientAnswering(HttpStatus.OK, offerJson)

        val outcome = client.getOffer("of-002")

        assertIs<OfferOutcome.Found>(outcome)
        val offer = outcome.offer
        assertEquals("of-002", offer.offerId)
        assertEquals("rt-to-mi-001", offer.routeId)
        assertEquals(0, BigDecimal("49.90").compareTo(offer.price))
        assertEquals("EUR", offer.currency)
        assertEquals(1, offer.termsVersion)
        // The machine-readable rule, which is the only form a money decision may be made from.
        assertEquals(true, offer.refundPolicy.refundable)
        assertEquals(5, offer.refundPolicy.cutoffMinutesBeforeDeparture)
    }

    @Test
    fun `the departure time and zone survive the wire as a real zone, not a string`() = runBlocking {
        // The whole reason Catalog denormalizes these onto the offer: without the zone, `travelDate +
        // departureTime` is not a moment, and a refund cutoff measured against a non-moment is off by
        // the offset — and off differently on each side of a DST boundary.
        val client = catalogClientAnswering(HttpStatus.OK, offerJson)

        val offer = assertIs<OfferOutcome.Found>(client.getOffer("of-002")).offer

        assertEquals(LocalTime.of(7, 0), offer.departureTime)
        assertEquals(ZoneId.of("Europe/Rome"), offer.departureZone)
        // Composed here once, for a winter date: 07:00 in Rome is 06:00 UTC.
        assertEquals(
            "2026-01-15T06:00:00Z",
            offer.departureAt(LocalDate.of(2026, 1, 15)).toInstant().toString(),
        )
    }

    @Test
    fun `a 404 OFFER_NOT_FOUND is the one refusal this endpoint has`() = runBlocking {
        val client = catalogClientAnswering(
            HttpStatus.NOT_FOUND,
            """{"code":"OFFER_NOT_FOUND","message":"Offer of-999 does not exist"}""",
        )

        assertEquals(OfferOutcome.NotFound, client.getOffer("of-999"))
    }

    @Test
    fun `a broken catalog stops the checkout instead of pricing it`() = runBlocking {
        // Catalog answers 500 OFFER_ROUTE_MISSING when an offer points at a route that does not
        // exist — so the departure fields the cutoff needs are absent. An unknown outcome, not an
        // answer: the checkout fails closed rather than freezing terms it could not evaluate.
        val client = catalogClientAnswering(
            HttpStatus.INTERNAL_SERVER_ERROR,
            """{"code":"OFFER_ROUTE_MISSING","message":"Offer of-002 references route rt-x"}""",
        )

        assertFailsWith<WebClientResponseException.InternalServerError> { client.getOffer("of-002") }
        Unit
    }

    @Test
    fun `an unrecognised 4xx is not turned into a verdict on the offer`() = runBlocking {
        // A contract drift or a credential problem, were Catalog ever to require one. Reporting it
        // as OFFER_NOT_FOUND would tell a customer their perfectly valid offer does not exist.
        val client = catalogClientAnswering(HttpStatus.FORBIDDEN, """{"code":"ACCESS_DENIED","message":"no scope"}""")

        assertFailsWith<DefinitiveRefusal> { client.getOffer("of-002") }
        Unit
    }

    @Test
    fun `the offer id travels in the path, not as a query parameter`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = catalogClientAnswering(HttpStatus.OK, offerJson, recordPath = { paths += it })

        client.getOffer("of-002")

        assertEquals(listOf("/api/catalog/offers/of-002"), paths)
    }

    @Test
    fun `a failing offer lookup is attempted once, unlike the money path`() = runBlocking {
        // Same decision as the seat map, for a different reason: this call sits in front of a
        // synchronous response the customer is waiting on, so retrying spends their latency budget
        // on a dependency that is already struggling. A 503 is in the money path's retryExceptions,
        // so the same fault on a release is attempted three times.
        val lookups = AtomicInteger()
        val releases = AtomicInteger()
        val resilience = ResilienceConfiguration()
        val builder = failingBuilder(lookups, releases)

        val catalogClient = WebClientCatalogClient(
            catalogUrl = "http://catalog.test",
            webClientBuilder = builder,
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )
        val moneyPathClient = WebClientInventoryClient(
            inventoryUrl = "http://catalog.test",
            webClientBuilder = builder,
            serviceAuthExchangeFilter = ExchangeFilterFunction.ofRequestProcessor { Mono.just(it) },
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            retryRegistry = resilience.retryRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )

        assertFailsWith<WebClientResponseException.ServiceUnavailable> { catalogClient.getOffer("of-002") }
        runCatching { moneyPathClient.releaseSeats("res-1", "rel-1") }

        assertEquals(1, lookups.get(), "the offer lookup must not be retried")
        assertEquals(3, releases.get(), "the money path retries: 1 initial + 2, per retryConfig")
    }

    /** Fresh Resilience4j registries per client, so one case's open breaker cannot leak into another. */
    private fun catalogClientAnswering(
        status: HttpStatus,
        body: String,
        recordPath: (String) -> Unit = {},
    ): WebClientCatalogClient {
        val handler = RouterFunctions.toHttpHandler(
            RouterFunctions.route(RequestPredicates.GET("/api/catalog/offers/{offerId}")) { request ->
                recordPath(request.uri().path)
                ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            },
        )
        val resilience = ResilienceConfiguration()
        return WebClientCatalogClient(
            catalogUrl = "http://catalog.test",
            webClientBuilder = WebClient.builder().clientConnector(HttpHandlerConnector(handler)),
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )
    }

    private fun failingBuilder(lookups: AtomicInteger, releases: AtomicInteger): WebClient.Builder {
        val unavailable = { counter: AtomicInteger ->
            counter.incrementAndGet()
            ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"error":"down"}""")
        }
        val handler = RouterFunctions.toHttpHandler(
            RouterFunctions
                .route(RequestPredicates.GET("/api/catalog/offers/{offerId}")) { unavailable(lookups) }
                .andRoute(RequestPredicates.DELETE("/api/inventory/reservations/{id}")) { unavailable(releases) },
        )
        return WebClient.builder().clientConnector(HttpHandlerConnector(handler))
    }
}
