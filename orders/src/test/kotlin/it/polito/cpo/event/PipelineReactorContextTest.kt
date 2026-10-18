package it.polito.cpo.event

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.ObservationView
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import it.polito.cpo.observability.CorrelationId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequestObservationContext
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.util.context.Context

/**
 * Pins #117 break A: a call the pipeline makes from inside a consumer must be a child of the
 * consumer's observation, not the root of a new trace.
 *
 * Exercised through a real [WebClient] with a stubbed exchange, because the property under test is
 * where WebClient looks for its parent — the Reactor context — and asserting on the context alone would
 * only restate the implementation. The second test is the control: the context the listeners built
 * before the fix, which must leave the call parentless, so this test cannot pass vacuously.
 */
class PipelineReactorContextTest {

    private val started = mutableListOf<Observation.Context>()

    private val registry = ObservationRegistry.create().apply {
        // With no handler a registry is a no-op and no observation is ever current.
        observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
            override fun supportsContext(context: Observation.Context) = true
            override fun onStart(context: Observation.Context) {
                started += context
            }
        })
    }

    private val webClient = WebClient.builder()
        .observationRegistry(registry)
        .exchangeFunction { Mono.just(ClientResponse.create(HttpStatus.OK).build()) }
        .build()

    private fun callInventoryFrom(context: ReactorContext) = runBlocking(context) {
        webClient.get().uri("http://inventory/api/reservations").retrieve().toBodilessEntity().awaitSingle()
    }

    private fun clientCallParent(): ObservationView? =
        started.filterIsInstance<ClientRequestObservationContext>().single().parentObservation

    @Test
    fun `a call made inside the consumer is a child of the consumer's observation`() {
        val consumer = Observation.start("order-placed process", registry)
        consumer.openScope().use {
            callInventoryFrom(pipelineReactorContext("corr-1", registry))
        }
        consumer.stop()

        assertSame(consumer, clientCallParent())
    }

    @Test
    fun `control - the context built before the fix leaves the call without a parent`() {
        val consumer = Observation.start("order-placed process", registry)
        consumer.openScope().use {
            callInventoryFrom(ReactorContext(Context.of(CorrelationId.CONTEXT_KEY, "corr-1")))
        }
        consumer.stop()

        assertNull(clientCallParent())
    }

    @Test
    fun `without a current observation the context carries the correlation id alone`() {
        val context = pipelineReactorContext("corr-1", registry).context

        assertEquals("corr-1", context.get<String>(CorrelationId.CONTEXT_KEY))
        assertFalse(context.hasKey(ObservationThreadLocalAccessor.KEY))
    }
}
