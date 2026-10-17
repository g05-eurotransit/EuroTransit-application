package it.polito.cpo.payments.service.provider

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * What the adapter actually puts on the wire. A unit test on `PaymentService` can only show that the
 * order id reaches the adapter; the reason it is sent at all is that it must end up **on the charge**
 * at the provider (#142), and only the request body proves that.
 *
 * The stub is a real local HTTP server rather than a mocked WebClient, so the form encoding is
 * exercised: `metadata[order_id]` is a bracketed key in a form-encoded body, which is exactly the
 * part a mock would not have caught.
 */
class StripeSandboxProviderTest {

    private lateinit var server: DisposableServer
    private val bodies = ConcurrentHashMap<String, String>()
    private val headers = ConcurrentHashMap<String, String>()

    @BeforeEach
    fun startStub() {
        server = HttpServer.create()
            .port(0)
            .handle { request, response ->
                headers["Idempotency-Key"] = request.requestHeaders()["Idempotency-Key"] ?: ""
                request.receive().aggregate().asString(StandardCharsets.UTF_8)
                    .defaultIfEmpty("")
                    .flatMap { body ->
                        bodies[request.uri().substringBefore('?')] = body
                        response.header("Content-Type", "application/json")
                            .sendString(Mono.just("""{"id":"ch_test_1"}"""))
                            .then()
                    }
            }
            .bindNow()
    }

    @AfterEach
    fun stopStub() = server.disposeNow()

    private fun provider() = StripeSandboxProvider(
        sandboxUrl = "http://localhost:${server.port()}",
        secretKey = "sk_test_stub",
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults(),
        retryRegistry = RetryRegistry.ofDefaults(),
        bulkheadRegistry = BulkheadRegistry.ofDefaults(),
    )

    @Test
    fun `authorize sends the order id as charge metadata`() = runTest {
        val result = provider().authorize(
            orderId = "3fc771b7-1a95-451d-af2d-71776b754c14",
            amount = BigDecimal("24.90"),
            currency = "EUR",
            paymentMethodToken = "tok_visa",
            idempotencyKey = "pay-3fc771b7-1a95-451d-af2d-71776b754c14",
        )

        assertTrue(result.success)
        assertEquals("ch_test_1", result.providerReference)

        val body = URLDecoder.decode(bodies.getValue("/charges"), StandardCharsets.UTF_8)
        assertTrue(
            body.contains("metadata[order_id]=3fc771b7-1a95-451d-af2d-71776b754c14"),
            "the charge must carry its order id, got: $body",
        )
        // The rest of the charge is unchanged: amount in cents, lowercase currency, the token as the
        // source, and no `capture` parameter (it defaults to true — ADR-006).
        assertTrue(body.contains("amount=2490"), body)
        assertTrue(body.contains("currency=eur"), body)
        assertTrue(body.contains("source=tok_visa"), body)
        assertTrue(!body.contains("capture="), body)
        // Unchanged too: the provider-side replay key, which is what makes a retry safe after a lost
        // answer. The metadata must not have displaced it.
        assertEquals("stripe_pay-3fc771b7-1a95-451d-af2d-71776b754c14", headers["Idempotency-Key"])
    }
}
