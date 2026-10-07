package it.polito.cpo.service

import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import it.polito.cpo.event.PoisonEventException
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClientRequestException

/**
 * Pins the dead-letter label for the exceptions the pipeline actually raises, by their real class
 * names rather than string literals, so a rename on either side fails here instead of silently turning
 * an incident's `failure_class` into `unknown`.
 */
class FailureClassifierTest {

    @Test
    fun `resilience refusals are dependency errors, the bulkhead as much as the breaker`() {
        // The breaker was classified and the bulkhead was not: a record exhausted by a saturated pool
        // reached the operator labelled `unknown`, although both mean the same thing.
        assertEquals(
            FailureClassifier.DEPENDENCY_ERROR,
            FailureClassifier.forDeadLetter(CallNotPermittedException::class.java.name),
        )
        assertEquals(
            FailureClassifier.DEPENDENCY_ERROR,
            FailureClassifier.forDeadLetter(BulkheadFullException::class.java.name),
        )
    }

    @Test
    fun `transport failures and timeouts are dependency errors`() {
        assertEquals(
            FailureClassifier.DEPENDENCY_ERROR,
            FailureClassifier.forDeadLetter(WebClientRequestException::class.java.name),
        )
        assertEquals(
            FailureClassifier.DEPENDENCY_ERROR,
            FailureClassifier.forDeadLetter(TimeoutException::class.java.name),
        )
    }

    @Test
    fun `a poison payload keeps its own label, and anything unrecognised stays unknown`() {
        assertEquals(
            FailureClassifier.POISON_PAYLOAD,
            FailureClassifier.forDeadLetter(PoisonEventException::class.java.name),
        )
        assertEquals(FailureClassifier.UNKNOWN, FailureClassifier.forDeadLetter(IllegalStateException::class.java.name))
        assertEquals(FailureClassifier.UNKNOWN, FailureClassifier.forDeadLetter(null))
    }
}
