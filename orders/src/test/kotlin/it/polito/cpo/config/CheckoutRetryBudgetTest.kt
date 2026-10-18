package it.polito.cpo.config

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.r2dbc.spi.R2dbcBadGrammarException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import it.polito.cpo.event.PoisonEventException
import java.sql.SQLException
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.FixedBackOff
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * Pins the checkout listeners' retry budget: a dependency that is not available is retried long enough
 * to ride out a short outage, anything else is dead-lettered as soon as before, and the long budget
 * stays bounded however the failure changes shape while it runs.
 */
class CheckoutRetryBudgetTest {

    /** The production defaults, read from the same constants the bean's `@Value` falls back to. */
    private fun defaultDependencyBackOff() = dependencyRetryBackOff(
        initialIntervalMs = DEPENDENCY_RETRY_INITIAL_INTERVAL_MS.toLong(),
        maxIntervalMs = DEPENDENCY_RETRY_MAX_INTERVAL_MS.toLong(),
        maxRetries = DEPENDENCY_RETRY_MAX_RETRIES.toLong(),
        jitterMs = DEPENDENCY_RETRY_JITTER_MS.toLong(),
    )

    @Test
    fun `by default the dependency back-off is six jittered, doubling, capped waits of about a minute`() {
        // The base waits are 1, 2, 4, 8, 16 and 30 s. Spring moves each by up to a quarter, scaled with
        // it, never below the first interval nor above the cap.
        val lower = listOf(1_000L, 1_500L, 3_000L, 6_000L, 12_000L, 22_500L)
        val upper = listOf(1_250L, 2_500L, 5_000L, 10_000L, 20_000L, 30_000L)
        val runs = List(500) {
            val execution = defaultDependencyBackOff().start()
            generateSequence { execution.nextBackOff().takeIf { it != BackOffExecution.STOP } }.take(100).toList()
        }

        runs.forEach { intervals ->
            assertEquals(6, intervals.size)
            intervals.indices.forEach { i ->
                assertTrue(intervals[i] in lower[i]..upper[i], "wait $i was ${intervals[i]}: $intervals")
            }
        }
        // The jitter is there: the same budget does not repeat the same waits.
        assertTrue(runs.toSet().size > 1)
    }

    /** The number of deliveries a record gets before the handler dead-letters it. */
    private fun deliveriesUntilDeadLettered(handler: DefaultErrorHandler, failures: (Int) -> Exception): Int {
        val record = ConsumerRecord("order-placed", 0, 0L, "k", "v")
        var delivery = 0
        while (delivery < 50) {
            delivery++
            val thrown = ListenerExecutionFailedException("Listener failed", "orders", failures(delivery))
            val recovered = handler.handleOne(
                thrown, record, mock(Consumer::class.java), mock(MessageListenerContainer::class.java),
            )
            if (recovered) return delivery
        }
        error("never dead-lettered")
    }

    /** Default budget: 2 deliveries. Dependency budget: 6. Small enough to tell apart, no waiting. */
    private fun handler() = checkoutErrorHandler(
        recoverer = ConsumerAwareRecordRecoverer { _, _, _ -> },
        defaultBackOff = FixedBackOff(0, 1),
        dependencyBackOff = FixedBackOff(0, 5),
    )

    private val openBreaker = CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("payments"))

    @Test
    fun `a dependency that is not available gets the long budget`() {
        assertEquals(6, deliveriesUntilDeadLettered(handler()) { TimeoutException("Payments did not answer") })
        assertEquals(6, deliveriesUntilDeadLettered(handler()) { openBreaker })
        assertEquals(6, deliveriesUntilDeadLettered(handler()) { R2dbcNonTransientResourceException("primary gone") })
    }

    private fun httpError(status: Int) =
        WebClientResponseException.create(status, "status $status", HttpHeaders.EMPTY, ByteArray(0), null)

    @Test
    fun `a 5xx, a timeout or a rate limit the dependency answered is worth waiting for`() {
        assertEquals(6, deliveriesUntilDeadLettered(handler()) { httpError(503) })
        assertEquals(6, deliveriesUntilDeadLettered(handler()) { httpError(408) })
        assertEquals(6, deliveriesUntilDeadLettered(handler()) { httpError(429) })
        assertEquals(6, deliveriesUntilDeadLettered(handler()) { tokenError("server_error") })
        assertEquals(6, deliveriesUntilDeadLettered(handler()) { TransientDataAccessResourceException("failover") })
    }

    @Test
    fun `a deployment or data bug labelled as a dependency failure keeps the short budget`() {
        // A 4xx with no business code: a broken service credential or a contract drift.
        assertEquals(2, deliveriesUntilDeadLettered(handler()) { httpError(401) })
        assertEquals(2, deliveriesUntilDeadLettered(handler()) { httpError(400) })
        // Keycloak refused the service credential.
        assertEquals(2, deliveriesUntilDeadLettered(handler()) { tokenError("invalid_client") })
        // A statement the database rejects, not a database that is away.
        assertEquals(2, deliveriesUntilDeadLettered(handler()) { BadSqlGrammarException("q", "select", SQLException()) })
        assertEquals(2, deliveriesUntilDeadLettered(handler()) { R2dbcBadGrammarException("bad sql") })
    }

    private fun tokenError(code: String) =
        ClientAuthorizationException(OAuth2Error(code), "orders-service")

    @Test
    fun `any other failure keeps the short budget, and a poison payload gets none`() {
        assertEquals(2, deliveriesUntilDeadLettered(handler()) { IllegalStateException("unexpected state") })
        assertEquals(1, deliveriesUntilDeadLettered(handler()) { PoisonEventException("bad", IllegalArgumentException()) })
    }

    @Test
    fun `a breaker cycling between open and half-open does not restart the budget`() {
        val cycling: (Int) -> Exception = { if (it % 2 == 0) openBreaker else TimeoutException("half-open probe timed out") }

        assertEquals(6, deliveriesUntilDeadLettered(handler(), cycling))
    }

    @Test
    fun `control - with Spring Kafka's default reset the same cycle never reaches the operator`() {
        val resetting = handler().apply { setResetStateOnExceptionChange(true) }
        val cycling: (Int) -> Exception = { if (it % 2 == 0) openBreaker else TimeoutException("half-open probe timed out") }

        val outcome = runCatching { deliveriesUntilDeadLettered(resetting, cycling) }

        assertEquals("never dead-lettered", outcome.exceptionOrNull()?.message)
    }
}
