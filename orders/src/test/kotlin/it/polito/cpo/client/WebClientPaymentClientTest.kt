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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Wire-level tests for the reversal call, run in-process through [HttpHandlerConnector] like
 * [WebClientInventoryClientTest]. They pin the bodies Payments actually answers (`PaymentController.refund`,
 * `PaymentRefundResponse`), because an abandoned checkout (#140) decides on them: which answers mean
 * "Payments holds no settled authorization", and which do not.
 */
class WebClientPaymentClientTest {

    private val orderId: UUID = UUID.randomUUID()

    @Test
    fun `a 404 for a missing authorization is a definitive answer that says so`() = runBlocking {
        val client = clientAnswering(
            HttpStatus.NOT_FOUND,
            """{"status":"NOT_FOUND","refundReference":null,"errorCode":"AUTHORIZATION_NOT_FOUND"}""",
        )

        val outcome = client.refund(orderId, "refund-$orderId")

        assertIs<ReversalOutcome.Failed>(outcome)
        assertTrue(outcome.isNoAuthorization())
        assertFalse(outcome.isUnsettledAuthorization())
    }

    @Test
    fun `a 422 for an authorization that is not AUTHORIZED says so`() = runBlocking {
        val client = clientAnswering(
            HttpStatus.UNPROCESSABLE_ENTITY,
            """{"status":"FAILED","refundReference":null,"errorCode":"INVALID_AUTHORIZATION_STATUS"}""",
        )

        val outcome = client.refund(orderId, "refund-$orderId")

        assertIs<ReversalOutcome.Failed>(outcome)
        assertTrue(outcome.isUnsettledAuthorization())
        assertFalse(outcome.isNoAuthorization())
    }

    @Test
    fun `a reversal the provider refused is neither`() = runBlocking {
        val client = clientAnswering(
            HttpStatus.UNPROCESSABLE_ENTITY,
            """{"status":"FAILED","refundReference":null,"errorCode":"charge_already_refunded"}""",
        )

        val outcome = client.refund(orderId, "refund-$orderId")

        assertIs<ReversalOutcome.Failed>(outcome)
        assertFalse(outcome.isNoAuthorization())
        assertFalse(outcome.isUnsettledAuthorization())
    }

    @Test
    fun `a 404 without Payments' body is an unknown outcome, not a missing authorization`() {
        // A route or gateway 404 carries no refusal code: it must be retried, never read as "no money".
        val client = clientAnswering(HttpStatus.NOT_FOUND, "")

        assertFailsWith<WebClientResponseException> { runBlocking { client.refund(orderId, "refund-$orderId") } }
    }

    private fun clientAnswering(status: HttpStatus, body: String): WebClientPaymentClient {
        val handler = RouterFunctions.toHttpHandler(
            RouterFunctions.route(RequestPredicates.POST("/api/payments/refund")) {
                val response = ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                if (body.isEmpty()) response.build() else response.bodyValue(body)
            },
        )
        val builder = WebClient.builder().clientConnector(HttpHandlerConnector(handler))

        // Fresh registries per client so a breaker tripped by one case cannot leak into another.
        val resilience = ResilienceConfiguration()
        return WebClientPaymentClient(
            paymentsUrl = "http://payments.test",
            webClientBuilder = builder,
            serviceAuthExchangeFilter = ExchangeFilterFunction.ofRequestProcessor { Mono.just(it) },
            circuitBreakerRegistry = resilience.circuitBreakerRegistry(),
            retryRegistry = resilience.retryRegistry(),
            bulkheadRegistry = resilience.bulkheadRegistry(),
        )
    }
}
