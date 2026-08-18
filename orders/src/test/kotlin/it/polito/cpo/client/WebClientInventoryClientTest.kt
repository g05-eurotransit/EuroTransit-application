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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Wire-level tests for the confirmation call: a real HTTP exchange (status line, headers, codecs) run
 * in-process through [HttpHandlerConnector], with no socket and no server.
 *
 * This is the cheapest place to prove the property the whole design rests on — that an unknown outcome
 * never becomes a business result — because it is the only layer that sees the actual status code and
 * response body. No mock HTTP server dependency is added: the repo deliberately has none, and
 * `HttpHandlerConnector` ships with the Spring test support already on the classpath.
 */
class WebClientInventoryClientTest {

    private val reservationId = "res-1"
    private val orderId: UUID = UUID.randomUUID()

    @Test
    fun `a 200 CONFIRMED is a definitive success`() = runBlocking {
        val client = clientAnswering(HttpStatus.OK, """{"reservationId":"res-1","status":"CONFIRMED","expiresAt":"2026-08-05T10:00:00"}""")

        val outcome = client.confirmReservation(reservationId, orderId)

        assertIs<ConfirmReservationOutcome.Confirmed>(outcome)
        assertEquals(reservationId, outcome.reservationId)
    }

    @Test
    fun `a 409 with a known code is a definitive refusal carrying that code`() = runBlocking {
        val client = clientAnswering(
            HttpStatus.CONFLICT,
            """{"code":"RESERVATION_EXPIRED","message":"Reservation has expired","correlationId":"corr-1"}""",
        )

        val outcome = client.confirmReservation(reservationId, orderId)

        assertIs<ConfirmReservationOutcome.Refused>(outcome)
        assertEquals(InventoryRefusalCode.RESERVATION_EXPIRED, outcome.code)
    }

    @Test
    fun `every documented conflict code maps to its refusal`() = runBlocking {
        val expected = mapOf(
            "RESERVATION_CANCELLED" to InventoryRefusalCode.RESERVATION_CANCELLED,
            "RESERVATION_ORDER_CONFLICT" to InventoryRefusalCode.RESERVATION_ORDER_CONFLICT,
            "RESERVATION_SEAT_CONFLICT" to InventoryRefusalCode.RESERVATION_SEAT_CONFLICT,
        )
        expected.forEach { (wireCode, code) ->
            val outcome = clientAnswering(HttpStatus.CONFLICT, """{"code":"$wireCode","message":"m"}""")
                .confirmReservation(reservationId, orderId)
            assertIs<ConfirmReservationOutcome.Refused>(outcome, "for $wireCode")
            assertEquals(code, outcome.code)
        }
    }

    @Test
    fun `a 404 RESERVATION_NOT_FOUND is a definitive refusal`() = runBlocking {
        val client = clientAnswering(HttpStatus.NOT_FOUND, """{"code":"RESERVATION_NOT_FOUND","message":"m"}""")

        val outcome = client.confirmReservation(reservationId, orderId)

        assertIs<ConfirmReservationOutcome.Refused>(outcome)
        assertEquals(InventoryRefusalCode.RESERVATION_NOT_FOUND, outcome.code)
    }

    @Test
    fun `a 409 with an empty body is unknown, not a refusal`() = runBlocking {
        // The case that is easiest to get wrong: no decodable code means the request never reached a
        // business decision, so it must not be turned into a compensation.
        val client = clientAnswering(HttpStatus.CONFLICT, "")

        assertFailsWith<WebClientResponseException> { client.confirmReservation(reservationId, orderId) }
        Unit
    }

    @Test
    fun `a 409 with an unrecognised code is unknown, not a refusal`() = runBlocking {
        val client = clientAnswering(HttpStatus.CONFLICT, """{"code":"SOMETHING_NEW","message":"m"}""")

        assertFailsWith<RuntimeException> { client.confirmReservation(reservationId, orderId) }
        Unit
    }

    @Test
    fun `a 401 is unknown, not a refusal`() = runBlocking {
        // A broken service credential means Inventory never evaluated the reservation. Refunding on it
        // would burn a paid checkout to hide a deployment fault.
        val client = clientAnswering(HttpStatus.UNAUTHORIZED, "")

        assertFailsWith<WebClientResponseException> { client.confirmReservation(reservationId, orderId) }
        Unit
    }

    @Test
    fun `a 5xx is unknown and stays a dependency failure`() = runBlocking {
        // Deliberately not intercepted, so Resilience4j retry and the circuit breaker still see a fault.
        val client = clientAnswering(HttpStatus.SERVICE_UNAVAILABLE, """{"code":"INTERNAL_ERROR","message":"m"}""")

        assertFailsWith<WebClientResponseException> { client.confirmReservation(reservationId, orderId) }
        Unit
    }

    @Test
    fun `a 200 whose status is not CONFIRMED is unknown, not a success`() = runBlocking {
        val client = clientAnswering(HttpStatus.OK, """{"reservationId":"res-1","status":"HELD","expiresAt":"2026-08-05T10:00:00"}""")

        assertFailsWith<IllegalStateException> { client.confirmReservation(reservationId, orderId) }
        Unit
    }

    // --- harness ----------------------------------------------------------------------------------

    /**
     * Builds the real client against an in-process handler that always answers [status] with [body].
     * Retry is configured for 3 attempts on transient faults, so a 5xx here is exercised through the
     * same operator chain production uses.
     */
    private fun clientAnswering(status: HttpStatus, body: String): WebClientInventoryClient {
        val handler = RouterFunctions.toHttpHandler(
            RouterFunctions.route(
                RequestPredicates.PUT("/api/inventory/reservations/{id}/confirmation"),
            ) {
                val response = ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                if (body.isEmpty()) response.build() else response.bodyValue(body)
            },
        )
        val builder = WebClient.builder().clientConnector(HttpHandlerConnector(handler))

        // Fresh registries per client so a breaker tripped by one case cannot leak into another.
        val resilience = ResilienceConfiguration()
        return WebClientInventoryClient(
            inventoryUrl = "http://inventory.test",
            webClientBuilder = builder,
            // In production this is the client-credentials Bearer filter; here it just forwards.
            // The client depends on the ExchangeFilterFunction interface precisely so this is a
            // one-liner: the concrete Spring Security filter is final and cannot be substituted.
            serviceAuthExchangeFilter = ExchangeFilterFunction.ofRequestProcessor { Mono.just(it) },
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            retryRegistry = resilience.retryRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )
    }
}
