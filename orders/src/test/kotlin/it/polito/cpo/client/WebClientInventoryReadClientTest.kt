package it.polito.cpo.client

import it.polito.cpo.config.ResilienceConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.HttpHandlerConnector
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.time.LocalDate
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Wire-level tests for the seat-map read, run in-process through [HttpHandlerConnector] like
 * [WebClientInventoryClientTest].
 *
 * The load-bearing case here is `a failing read is attempted once, unlike the money path`: the
 * decision this client exists for is not visible in its own source, because it is the *absence* of a
 * `RetryOperator`. An absence cannot be reviewed, so it is asserted instead.
 */
class WebClientInventoryReadClientTest {

    private val travelDate: LocalDate = LocalDate.of(2026, 9, 20)

    @Test
    fun `a 200 maps the wire flag onto the Orders-side seat`() = runBlocking {
        // `isAvailable` is the name Inventory puts on the wire, pinned by its own SeatControllerTest.
        val client = readClientAnswering(
            HttpStatus.OK,
            """{"routeId":"R1","travelDate":"2026-09-20","seats":[
                 {"seatId":"1A","isAvailable":true},
                 {"seatId":"1B","isAvailable":false}
               ]}""",
        )

        val outcome = client.getSeatAvailability("R1", travelDate)

        assertIs<SeatAvailabilityOutcome.Found>(outcome)
        assertEquals(listOf(SeatAvailability("1A", true), SeatAvailability("1B", false)), outcome.seats)
    }

    @Test
    fun `an empty seat list is a valid answer, not a failure`() = runBlocking {
        val client = readClientAnswering(HttpStatus.OK, """{"routeId":"R1","travelDate":"2026-09-20","seats":[]}""")

        val outcome = client.getSeatAvailability("R1", travelDate)

        assertIs<SeatAvailabilityOutcome.Found>(outcome)
        assertTrue(outcome.seats.isEmpty())
    }

    @Test
    fun `a 404 ROUTE_NOT_FOUND is the one refusal this endpoint has`() = runBlocking {
        val client = readClientAnswering(
            HttpStatus.NOT_FOUND,
            """{"code":"ROUTE_NOT_FOUND","message":"No seats found for route R1"}""",
        )

        assertEquals(SeatAvailabilityOutcome.NotFound, client.getSeatAvailability("R1", travelDate))
    }

    @Test
    fun `an unknown outcome is thrown rather than reported as no seats`() = runBlocking {
        // The dangerous alternative is returning an empty list here: the customer would be told the
        // train is sold out because Inventory was down, and the seat picker has no way to tell.
        val client = readClientAnswering(HttpStatus.SERVICE_UNAVAILABLE, """{"error":"down"}""")

        assertFailsWith<WebClientResponseException.ServiceUnavailable> {
            client.getSeatAvailability("R1", travelDate)
        }
        Unit
    }

    @Test
    fun `an unrecognised 4xx code is not treated as an answer`() = runBlocking {
        // A credential or contract problem reaches a business decision it has no right to make.
        val client = readClientAnswering(HttpStatus.FORBIDDEN, """{"code":"ACCESS_DENIED","message":"no scope"}""")

        assertFailsWith<DefinitiveRefusal> { client.getSeatAvailability("R1", travelDate) }
        Unit
    }

    @Test
    fun `the route and travel date are carried as query parameters`() = runBlocking {
        val queries = mutableListOf<String?>()
        val client = readClientAnswering(
            HttpStatus.OK,
            """{"routeId":"R1","travelDate":"2026-09-20","seats":[]}""",
            recordQuery = { queries += it },
        )

        client.getSeatAvailability("R1", travelDate)

        // Both are required: seats are keyed by (route_id, travel_date, seat_id) since #64, so a
        // route alone does not identify a bookable seat.
        assertEquals(1, queries.size)
        assertTrue(queries.single()!!.contains("routeId=R1"), "query was ${queries.single()}")
        assertTrue(queries.single()!!.contains("travelDate=2026-09-20"), "query was ${queries.single()}")
    }

    @Test
    fun `a failing read is attempted once, unlike the money path`() = runBlocking {
        // This is the whole reason the read has its own client and its own Resilience4j instances.
        // A 503 is in the money path's retryExceptions, so the same fault on the hold call is
        // attempted three times; retrying browsing traffic is how a struggling Inventory becomes an
        // outage, so the read must not amplify it.
        val reads = AtomicInteger()
        val holds = AtomicInteger()
        val resilience = ResilienceConfiguration()
        val builder = failingBuilder(reads, holds)

        val readClient = WebClientInventoryReadClient(
            inventoryUrl = "http://inventory.test",
            webClientBuilder = builder,
            serviceAuthExchangeFilter = ExchangeFilterFunction.ofRequestProcessor { Mono.just(it) },
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )
        val moneyPathClient = WebClientInventoryClient(
            inventoryUrl = "http://inventory.test",
            webClientBuilder = builder,
            serviceAuthExchangeFilter = ExchangeFilterFunction.ofRequestProcessor { Mono.just(it) },
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            retryRegistry = resilience.retryRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )

        assertFailsWith<WebClientResponseException.ServiceUnavailable> {
            readClient.getSeatAvailability("R1", travelDate)
        }
        runCatching { moneyPathClient.releaseSeats("res-1", "rel-1") }

        assertEquals(1, reads.get(), "the seat map must not be retried")
        assertEquals(3, holds.get(), "the money path retries: 1 initial + 2, per retryConfig")
    }

    @Test
    fun `the default read budget allows service authentication taking longer than one second`() = runBlocking {
        val calls = AtomicInteger()
        val resilience = ResilienceConfiguration()
        val client = WebClientInventoryReadClient(
            inventoryUrl = "http://inventory.test",
            webClientBuilder = WebClient.builder().exchangeFunction {
                calls.incrementAndGet()
                Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("""{"routeId":"R1","travelDate":"2026-09-20","seats":[]}""")
                    .build())
            },
            serviceAuthExchangeFilter = ExchangeFilterFunction { request, next ->
                Mono.delay(Duration.ofMillis(1250)).flatMap { next.exchange(request) }
            },
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )

        assertIs<SeatAvailabilityOutcome.Found>(client.getSeatAvailability("R1", travelDate))
        assertEquals(1, calls.get())
    }

    @Test
    fun `an overridden timeout cancels stuck authentication without calling Inventory or retrying`() = runBlocking {
        val calls = AtomicInteger()
        val authAttempts = AtomicInteger()
        val cancelled = AtomicBoolean()
        val resilience = ResilienceConfiguration()
        val client = WebClientInventoryReadClient(
            inventoryUrl = "http://inventory.test",
            webClientBuilder = WebClient.builder().exchangeFunction {
                calls.incrementAndGet()
                Mono.error(AssertionError("Inventory must not be called before authentication completes"))
            },
            serviceAuthExchangeFilter = ExchangeFilterFunction { _, _ ->
                authAttempts.incrementAndGet()
                Mono.never<ClientResponse>().doOnCancel { cancelled.set(true) }
            },
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
            timeoutMillis = 50,
        )

        assertFailsWith<TimeoutException> { client.getSeatAvailability("R1", travelDate) }
        assertEquals(1, authAttempts.get())
        assertEquals(0, calls.get())
        assertTrue(cancelled.get(), "the expired read must cancel its authentication publisher")
    }

    /**
     * Fresh Resilience4j registries per client, so a breaker opened by one case cannot leak into
     * another through shared state.
     */
    private fun readClientAnswering(
        status: HttpStatus,
        body: String,
        recordQuery: (String?) -> Unit = {},
    ): WebClientInventoryReadClient {
        val handler = RouterFunctions.toHttpHandler(
            RouterFunctions.route(RequestPredicates.GET("/api/inventory/availability")) { request ->
                recordQuery(request.uri().query)
                ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            },
        )
        val resilience = ResilienceConfiguration()
        return WebClientInventoryReadClient(
            inventoryUrl = "http://inventory.test",
            webClientBuilder = WebClient.builder().clientConnector(HttpHandlerConnector(handler)),
            // In production this is the client-credentials Bearer filter; here it just forwards.
            serviceAuthExchangeFilter = ExchangeFilterFunction.ofRequestProcessor { Mono.just(it) },
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )
    }

    private fun failingBuilder(reads: AtomicInteger, holds: AtomicInteger): WebClient.Builder {
        val unavailable = { counter: AtomicInteger ->
            counter.incrementAndGet()
            ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"error":"down"}""")
        }
        val handler = RouterFunctions.toHttpHandler(
            RouterFunctions
                .route(RequestPredicates.GET("/api/inventory/availability")) { unavailable(reads) }
                .andRoute(RequestPredicates.DELETE("/api/inventory/reservations/{id}")) { unavailable(holds) },
        )
        return WebClient.builder().clientConnector(HttpHandlerConnector(handler))
    }
}
