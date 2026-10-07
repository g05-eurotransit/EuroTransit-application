package it.polito.cpo.event

import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import it.polito.cpo.observability.CorrelationId
import kotlinx.coroutines.reactor.ReactorContext
import reactor.util.context.Context

/**
 * The Reactor context a consumer runs its pipeline in: the correlation id, and the observation Spring
 * Kafka opened for the record being processed.
 *
 * The listeners start the pipeline with `runBlocking(...)`, which builds a Reactor context from
 * scratch. WebClient looks for its parent observation in that context, not on the thread, so a context
 * carrying only the correlation id made every call to Inventory and Payments start a new trace — the
 * consumer span had no children and each client span had no parent (#117, break A). Putting the
 * consumer's observation under [ObservationThreadLocalAccessor.KEY] makes those calls its children.
 *
 * The observation is read from the registry while the listener is inside the container's observation
 * scope, which is the only place it is current. When observation is disabled there is none, and the
 * context carries the correlation id alone, exactly as before.
 *
 * This joins the consumer to the calls it makes. It does not join the consumer to the HTTP request
 * that placed the order: that hop crosses the outbox, which carries no trace context (#117, break B).
 */
internal fun pipelineReactorContext(correlationId: String, observationRegistry: ObservationRegistry): ReactorContext {
    val context = Context.of(CorrelationId.CONTEXT_KEY, correlationId)
    val consumerObservation = observationRegistry.currentObservation ?: return ReactorContext(context)
    return ReactorContext(context.put(ObservationThreadLocalAccessor.KEY, consumerObservation))
}
