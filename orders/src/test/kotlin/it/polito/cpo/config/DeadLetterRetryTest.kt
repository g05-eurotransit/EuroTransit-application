package it.polito.cpo.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.util.backoff.BackOffExecution

/**
 * Pins the dead-letter consumer's retry policy: it never stops, so an incident that cannot be written
 * yet is retried instead of being logged and committed away; and each failed attempt is counted.
 */
class DeadLetterRetryTest {

    @Test
    fun `the back-off never runs out, doubles, and is capped`() {
        val execution = dltRetryBackOff(initialIntervalMs = 1_000, maxIntervalMs = 30_000).start()
        val intervals = List(1_000) { execution.nextBackOff() }

        intervals.forEach { assertNotEquals(BackOffExecution.STOP, it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), intervals.take(6))
        assertEquals(30_000L, intervals.last())
    }

    private val registry = SimpleMeterRegistry()

    /** True when the handler gave up on the record (logged and recovered it); false when it will retry. */
    private fun recovered(handler: DefaultErrorHandler, thrown: Exception): Boolean =
        handler.handleOne(
            ListenerExecutionFailedException("Listener failed", "orders-dlt", thrown),
            ConsumerRecord("order-placed-dlt", 0, 0L, "k", "v"),
            mock(Consumer::class.java),
            mock(MessageListenerContainer::class.java),
        )

    @Test
    fun `an exception Spring Kafka calls fatal is retried too, not logged and committed`() {
        val handler = deadLetterErrorHandler(dltRetryBackOff(initialIntervalMs = 1, maxIntervalMs = 1), registry)

        assertFalse(recovered(handler, ClassCastException("a bug, not bad data")))
        assertFalse(recovered(handler, IllegalStateException("database unavailable")))
    }

    @Test
    fun `control - with the default classification the same exception is given up on at once`() {
        val handler = DefaultErrorHandler(dltRetryBackOff(initialIntervalMs = 1, maxIntervalMs = 1))

        assertTrue(recovered(handler, ClassCastException("a bug, not bad data")))
    }

    private fun recordFailed(topic: String): Double? =
        registry.find("orders.events.dlt").tags("topic", topic, "result", "record_failed").counter()?.count()

    @Test
    fun `every failed attempt is counted per dead-letter topic, from a zero series`() {
        registerDeadLetterRecordFailedSeries(registry)
        DEAD_LETTER_TOPICS.forEach { assertEquals(0.0, recordFailed(it), it) }

        val listener = countFailedDeadLetterRecording(registry)
        val record = ConsumerRecord("order-placed-dlt", 0, 0L, "k", "v")
        repeat(3) { listener.failedDelivery(record, IllegalStateException("db down"), it + 1) }

        assertEquals(3.0, recordFailed("order-placed-dlt"))
        assertEquals(0.0, recordFailed("refund-requested-dlt"))
    }
}
