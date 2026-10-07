package it.polito.cpo.service

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.ObservationView
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import io.micrometer.observation.transport.Kind
import io.micrometer.observation.transport.SenderContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import reactor.util.context.Context
import tools.jackson.databind.json.JsonMapper

/**
 * Pins #117 break B on the Orders side: the outbox row carries the `traceparent` of a publish span
 * that is a child of whatever was current when the row was written.
 *
 * The real `traceparent` is written by the tracing bridge's sender handler, which this module does not
 * have a test double for. The handler below stands in for it the same way: on start it writes into the
 * [SenderContext] carrier through the context's own setter — so what is proven is that [OrderOutbox]
 * hands the handler a carrier that reaches the row, and the right parent. That the bridge writes a
 * valid W3C value, and that the consumer joins it, is proven on the compose stack.
 */
class OrderOutboxTest {

    private class RecordedPublish(val name: String?, val parent: ObservationView?, val kind: Kind)

    private val publishes = mutableListOf<RecordedPublish>()

    private val registry = ObservationRegistry.create().apply {
        observationConfig().observationHandler(object : ObservationHandler<SenderContext<Any>> {
            override fun supportsContext(context: Observation.Context) = context is SenderContext<*>

            override fun onStart(context: SenderContext<Any>) {
                publishes += RecordedPublish(context.contextualName, context.parentObservation, context.kind)
                context.setter.set(context.carrier, "traceparent", TRACEPARENT)
            }
        })
        // A registry with no handler for the parent's context type would make it a no-op.
        observationConfig().observationHandler(ObservationHandler<Observation.Context> { true })
    }

    private val outbox = OrderOutbox(JsonMapper.builder().build(), registry)
    private val orderId = UUID.randomUUID()

    @Test
    fun `the row carries the traceparent of a publish span under the caller's observation`() {
        val caller = Observation.start("order-placed process", registry)

        val row = runBlocking(ReactorContext(Context.of(ObservationThreadLocalAccessor.KEY, caller))) {
            outbox.row("order-placed", orderId, mapOf("orderId" to orderId.toString()))
        }
        caller.stop()

        assertEquals(TRACEPARENT, row.traceparent)
        val publish = publishes.single()
        assertEquals("order-placed publish", publish.name)
        assertEquals(Kind.PRODUCER, publish.kind)
        assertSame(caller, publish.parent)
        // Routing and keying are what Debezium reads; they must not move.
        assertEquals("order-placed", row.type)
        assertEquals(orderId.toString(), row.aggregateId)
        assertEquals("Order", row.aggregateType)
    }

    @Test
    fun `without the key in the Reactor context there is no parent, even with a scope open on the thread`() {
        // The thread is not trusted: under unconfined dispatch it can carry an unrelated observation.
        val unrelated = Observation.start("unrelated", registry)

        unrelated.openScope().use {
            runBlocking { outbox.row("refund-requested", orderId, mapOf("orderId" to orderId.toString())) }
        }
        unrelated.stop()

        assertNull(publishes.single().parent)
    }

    @Test
    fun `with tracing off the row carries no traceparent, as before`() {
        val row = runBlocking {
            OrderOutbox(JsonMapper.builder().build(), ObservationRegistry.NOOP)
                .row("order-placed", orderId, mapOf("orderId" to orderId.toString()))
        }

        assertNull(row.traceparent)
    }

    private companion object {
        const val TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
    }
}
