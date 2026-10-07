package it.polito.cpo.event

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadFullException
import it.polito.cpo.service.FailureClassifier
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.support.KafkaHeaders

/**
 * Classifies dead letters from the headers the real [DeadLetterPublishingRecoverer] writes, not from
 * headers a test sets by hand.
 *
 * That difference hid a bug: the container hands the recoverer a [ListenerExecutionFailedException]
 * wrapping what the listener threw, and the recoverer puts the *wrapper's* class in
 * `DLT_EXCEPTION_FQCN` and the listener's in `DLT_EXCEPTION_CAUSE_FQCN`. Every test passed the inner
 * class as `DLT_EXCEPTION_FQCN`, so every incident was labelled `unknown` in production while every
 * test saw the right label.
 */
class DeadLetterHeadersTest {

    /** Runs [thrown] through the real recoverer exactly as the listener container would. */
    private fun deadLetterHeaders(thrown: Exception): Map<String, String> {
        var sent: ProducerRecord<*, *>? = null
        @Suppress("UNCHECKED_CAST")
        val operations = Proxy.newProxyInstance(
            KafkaOperations::class.java.classLoader,
            arrayOf(KafkaOperations::class.java),
        ) { _, method, args ->
            when {
                method.name == "send" && args?.size == 1 && args[0] is ProducerRecord<*, *> -> {
                    sent = args[0] as ProducerRecord<*, *>
                    CompletableFuture.completedFuture(null)
                }
                method.returnType == java.lang.Boolean.TYPE -> false
                method.name == "toString" -> "stub"
                else -> null
            }
        } as KafkaOperations<Any, Any>

        val wrapped = ListenerExecutionFailedException("Listener failed", "orders", thrown)
        DeadLetterPublishingRecoverer(operations).accept(ConsumerRecord("order-placed", 0, 0L, "k", "v"), null, wrapped)

        return checkNotNull(sent).headers().associate { it.key() to String(it.value()) }
    }

    private fun classify(thrown: Exception): String {
        val headers = deadLetterHeaders(thrown)
        return FailureClassifier.forDeadLetter(
            headers[KafkaHeaders.DLT_EXCEPTION_FQCN],
            headers[KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN],
        )
    }

    @Test
    fun `the recoverer names the wrapper first and the listener's exception as the cause`() {
        val headers = deadLetterHeaders(
            BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("inventory")),
        )

        assertEquals(ListenerExecutionFailedException::class.java.name, headers[KafkaHeaders.DLT_EXCEPTION_FQCN])
        assertEquals(BulkheadFullException::class.java.name, headers[KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN])
    }

    @Test
    fun `a saturated bulkhead reaches the operator as a dependency error`() {
        assertEquals(
            FailureClassifier.DEPENDENCY_ERROR,
            classify(BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("inventory"))),
        )
    }

    @Test
    fun `a poison payload keeps its own label through the wrapper`() {
        assertEquals(
            FailureClassifier.POISON_PAYLOAD,
            classify(PoisonEventException("Unparseable order-placed payload", IllegalArgumentException("bad json"))),
        )
    }
}
