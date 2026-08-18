package it.polito.cpo.event

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.contracts.events.NotificationRequestedEvent
import it.polito.cpo.notification.NotificationView
import it.polito.cpo.notification.NotificationStore
import it.polito.cpo.observability.CorrelationId
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID

/**
 * Consumes `notification-requested` events and records them into the read model.
 *
 * - Parses the raw String payload manually into the shared contract DTO
 *   (`money-path-contracts`). Orders' producer stamps a `__TypeId__` header; parsing a
 *   plain String keeps the consumer decoupled from that header regardless of the FQCN.
 * - Wraps processing in the event's own correlation id via MDC, reusing the exact key
 *   the `:observability` module bridges into ECS JSON logs, so a consumed event's log
 *   line is grep-able by the same correlation id as the originating checkout request.
 * - Deduplicates by `eventId` before recording (Kafka may redeliver).
 * - Emits a low-cardinality counter `notifications.events.consumed{topic,result}`
 *   (never labelled by principal/order/event id — that would explode metric cardinality).
 * - Never throws out of the listener for a bad payload: a poison message is logged and
 *   counted, not allowed to stall the partition (no DLQ in this slice — documented follow-up).
 */
@Component
class NotificationRequestedEventListener(
    private val objectMapper: ObjectMapper,
    private val store: NotificationStore,
    private val duplicateGuard: DuplicateEventGuard,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(NotificationRequestedEventListener::class.java)
    private val topic = "notification-requested"

    @KafkaListener(topics = ["notification-requested"], groupId = "\${spring.kafka.consumer.group-id}")
    fun onMessage(payload: String) {
        val event = try {
            objectMapper.readValue(payload, NotificationRequestedEvent::class.java)
        } catch (ex: Exception) {
            log.warn("Discarding unparseable notification-requested payload", ex)
            count("error")
            return
        }

        MDC.putCloseable(CorrelationId.CONTEXT_KEY, event.correlationId).use {
            if (!duplicateGuard.markIfNew(event.eventId)) {
                log.info("Duplicate notification-requested event ignored: eventId={}", event.eventId)
                count("duplicate")
                return
            }

            store.record(
                NotificationView(
                    id = event.eventId,
                    principalId = event.principalId,
                    orderId = event.orderId,
                    message = event.payload.message,
                    recipientEmail = event.payload.recipientEmail,
                    occurredAt = event.occurredAt,
                    receivedAt = LocalDateTime.now(),
                ),
            )
            log.info("Recorded notification for order {}", event.orderId)
            count("processed")
        }
    }

    private fun count(result: String) {
        meterRegistry.counter("notifications.events.consumed", "topic", topic, "result", result).increment()
    }
}
