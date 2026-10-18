package it.polito.cpo.integration

import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext

/**
 * The type of the observation a WebClient call made right here would take as its parent, or null if
 * there is none — read from the same Reactor context key WebClient reads.
 *
 * Recorded by the test doubles standing in for Inventory and Payments, so the integration tests can
 * pin that the real listeners, inside the real listener container, hand the pipeline the consumer's
 * observation (#117 break A). `PipelineReactorContextTest` proves the helper; this proves the wiring.
 */
internal suspend fun parentObservationContextType(): String? =
    currentCoroutineContext()[ReactorContext]?.context
        ?.getOrEmpty<Observation>(ObservationThreadLocalAccessor.KEY)?.orElse(null)
        ?.context?.javaClass?.simpleName
