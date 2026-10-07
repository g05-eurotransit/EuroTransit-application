package it.polito.cpo.service

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import io.micrometer.observation.transport.Kind
import io.micrometer.observation.transport.SenderContext
import io.r2dbc.postgresql.codec.Json
import it.polito.cpo.model.OutboxEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Builds the outbox row for an order-scoped event, and records the publish it stands for.
 *
 * One builder for every caller because Debezium's EventRouter reads these column values directly: it
 * routes by `type` to the topic of the same name and keys by `aggregate_id`. A caller that drifted on
 * either would silently stop being relayed, with no error anywhere.
 *
 * **Why a span is opened here (#117, break B).** With an outbox, writing the row *is* the publish:
 * Orders never sends to Kafka itself. So this is where the "Kafka publish span" the tracing
 * requirement asks for has to come from (`slo-observability.md` §Tracing). It is a short `PRODUCER`
 * observation, a child of whatever is current — the HTTP request on the checkout, refund and redrive
 * paths; the consumer's observation inside the pipeline since break A — and its W3C `traceparent` is
 * stored on the row. The connector places that column in the record header Spring Kafka's consumer
 * observation reads, so the consumer becomes a child of this span and the whole checkout is one trace.
 *
 * The `traceparent` is written by the tracing handler Spring Boot registers for any [SenderContext]
 * (`PropagatingSenderTracingObservationHandler`), the same mechanism Spring Kafka's own producer uses.
 * With tracing off nothing writes it, the column stays null, and the consumer starts a trace of its
 * own — exactly the behaviour before.
 */
@Component
class OrderOutbox(
    private val objectMapper: ObjectMapper,
    private val observationRegistry: ObservationRegistry,
) {

    suspend fun row(type: String, orderId: UUID, event: Any): OutboxEvent = OutboxEvent(
        aggregateType = "Order",
        aggregateId = orderId.toString(),
        type = type,
        payload = Json.of(objectMapper.writeValueAsString(event)),
        traceparent = publishTraceparent(type),
    )

    private suspend fun publishTraceparent(type: String): String? {
        val carrier = mutableMapOf<String, String>()
        val context = SenderContext<MutableMap<String, String>>(
            { map, key, value -> map?.put(key, value) },
            Kind.PRODUCER,
        ).apply { setCarrier(carrier) }

        Observation.createNotStarted(OBSERVATION_NAME, { context }, observationRegistry)
            .contextualName("$type publish")
            .lowCardinalityKeyValue("messaging.destination.name", type)
            .parentObservation(currentObservation())
            .start()
            .stop()

        return carrier[TRACEPARENT]
    }

    /**
     * The observation this publish belongs to. WebClient-style lookup first — the Reactor context the
     * coroutine carries, which `executeAndAwait` hands into the transaction — then the thread, for a
     * caller whose context does not carry the key.
     */
    private suspend fun currentObservation(): Observation? =
        currentCoroutineContext()[ReactorContext]?.context
            ?.getOrEmpty<Observation>(ObservationThreadLocalAccessor.KEY)?.orElse(null)
            ?: observationRegistry.currentObservation

    private companion object {
        const val OBSERVATION_NAME = "orders.outbox.publish"
        const val TRACEPARENT = "traceparent"
    }
}
