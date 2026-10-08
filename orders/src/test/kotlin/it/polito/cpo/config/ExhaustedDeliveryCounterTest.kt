package it.polito.cpo.config

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.polito.cpo.event.PoisonEventException
import it.polito.cpo.service.FailureClassifier
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.ListenerExecutionFailedException

/**
 * Pins F4's fix: the pod that gives up on a delivery counts it, labelled like the incident, and only
 * once the dead letter is actually published.
 *
 * Runs through the real [DeadLetterPublishingRecoverer] — only the template under it is a stand-in —
 * because "published" is the recoverer's decision: it throws when the send fails, and that is what
 * keeps a redelivered record from being counted twice.
 */
class ExhaustedDeliveryCounterTest {

    private val registry = SimpleMeterRegistry()

    /** A template whose sends complete, or fail, the way a broker would answer. */
    private fun template(sendFails: Boolean): KafkaOperations<Any, Any> {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            KafkaOperations::class.java.classLoader,
            arrayOf(KafkaOperations::class.java),
        ) { _, method, args ->
            when {
                method.name == "send" && args?.size == 1 && args[0] is ProducerRecord<*, *> ->
                    if (sendFails) CompletableFuture.failedFuture<Any>(RuntimeException("broker unavailable"))
                    else CompletableFuture.completedFuture(null)
                method.returnType == java.lang.Boolean.TYPE -> false
                method.name == "toString" -> "stub"
                else -> null
            }
        } as KafkaOperations<Any, Any>
    }

    private fun recover(thrown: Exception, sendFails: Boolean = false) =
        countingRecoverer(DeadLetterPublishingRecoverer(template(sendFails)), registry).accept(
            ConsumerRecord("order-placed", 0, 0L, "k", "v"),
            null,
            // What the listener container hands the error handler: the listener's exception, wrapped.
            ListenerExecutionFailedException("Listener failed", "orders", thrown),
        )

    private fun exhausted(failureClass: String): Double =
        registry.find("orders.events.exhausted")
            .tags("topic", "order-placed", "failure_class", failureClass)
            .counter()?.count() ?: 0.0

    @Test
    fun `a dead letter is counted on the pod that gave up, labelled by what the listener threw`() {
        recover(BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("inventory")))

        assertEquals(1.0, exhausted(FailureClassifier.DEPENDENCY_ERROR))
    }

    @Test
    fun `a poison payload is counted with its own label`() {
        recover(PoisonEventException("Unparseable order-placed payload", IllegalArgumentException("bad json")))

        assertEquals(1.0, exhausted(FailureClassifier.POISON_PAYLOAD))
    }

    @Test
    fun `a dead letter that failed to publish is not counted, so its redelivery is not counted twice`() {
        assertFailsWith<KafkaException> {
            recover(BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("inventory")), sendFails = true)
        }

        assertEquals(0.0, exhausted(FailureClassifier.DEPENDENCY_ERROR))
    }

    @Test
    fun `every series exists at zero before the first dead letter, so increase() can see it`() {
        registerExhaustedSeries(registry)

        val series = registry.find("orders.events.exhausted").counters()
        assertEquals(EXHAUSTABLE_TOPICS.size * FailureClassifier.DEAD_LETTER_LABELS.size, series.size)
        EXHAUSTABLE_TOPICS.forEach { topic ->
            FailureClassifier.DEAD_LETTER_LABELS.forEach { label ->
                val counter = registry.find("orders.events.exhausted").tags("topic", topic, "failure_class", label).counter()
                assertEquals(0.0, counter?.count(), "$topic/$label")
            }
        }
    }

    @Test
    fun `the consumer reaches the wrapped recoverer`() {
        var received: Consumer<*, *>? = null
        val consumer = mock(Consumer::class.java)

        countingRecoverer({ _, c, _ -> received = c }, registry).accept(
            ConsumerRecord("order-placed", 0, 0L, "k", "v"),
            consumer,
            ListenerExecutionFailedException("Listener failed", "orders", IllegalStateException("x")),
        )

        assertSame(consumer, received)
    }
}
