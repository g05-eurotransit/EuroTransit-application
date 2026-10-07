package it.polito.cpo.event

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.ObservationRegistry
import it.polito.cpo.contracts.events.RefundRequestedEvent
import it.polito.cpo.observability.CorrelationId
import it.polito.cpo.service.RefundOrchestrator
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Consumes `refund-requested` and drives the post-sale refund (ADR-015). Modelled directly on
 * [OrderPlacedEventListener] — same correlation-id and observation seeding into MDC and Reactor
 * context ([pipelineReactorContext]), same poison-versus-retryable distinction, same
 * `orders.events.consumed{topic,result}` counter — because the two consumers share every property
 * that matters for durability: at-least-once delivery, an offset committed only after processing, and
 * an unparseable payload classified non-retryable so it reaches the dead-letter topic immediately
 * instead of burning the retry budget on a certainty.
 *
 * A separate topic and a separate listener from `order-placed` on purpose (ADR-015): that pipeline's
 * consumer must keep treating `CONFIRMED` as terminal, so a redelivered `order-placed` never restarts
 * a completed checkout. Routing the refund through it would require `CONFIRMED` to become resumable.
 */
@Component
class RefundRequestedEventListener(
    private val objectMapper: ObjectMapper,
    private val orchestrator: RefundOrchestrator,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
) {
    private val log = LoggerFactory.getLogger(RefundRequestedEventListener::class.java)
    private val topic = "refund-requested"

    @KafkaListener(topics = ["refund-requested"], groupId = "\${spring.kafka.consumer.group-id}")
    fun onMessage(payload: String) {
        val event = try {
            objectMapper.readValue(payload, RefundRequestedEvent::class.java)
        } catch (ex: Exception) {
            count("poison")
            // The payload is deliberately not logged: it can carry personal data, and the record key
            // plus the dead-letter headers are enough to find it again.
            log.error("Unparseable refund-requested payload; routing to the dead-letter topic", ex)
            throw PoisonEventException("Unparseable refund-requested payload", ex)
        }

        MDC.putCloseable(CorrelationId.CONTEXT_KEY, event.correlationId).use {
            runBlocking(pipelineReactorContext(event.correlationId, observationRegistry)) {
                orchestrator.processRefundRequested(event)
            }
            count("processed")
        }
    }

    private fun count(result: String) {
        meterRegistry.counter("orders.events.consumed", "topic", topic, "result", result).increment()
    }
}
