package it.polito.cpo.event

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.contracts.events.OrderPlacedEvent
import it.polito.cpo.observability.CorrelationId
import it.polito.cpo.service.FailureClassifier
import it.polito.cpo.service.OrderService
import it.polito.cpo.service.ReconciliationService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Consumes `order-placed-dlt` and turns an exhausted delivery into a durable incident an operator can
 * act on.
 *
 * The listener deliberately holds **no** `InventoryClient`, no `PaymentClient`, and no write path into
 * the order state. That is not an oversight but the enforcement of "the dead-letter consumer does not
 * change the durable checkout state and issues no speculative release or refund commands"
 * (docs/design/reservation-confirmation.md). The rule is structural: the code to violate it is not
 * reachable from here. Redrive is an explicit operator decision, not something a consumer infers.
 *
 * Idempotent by construction: recording an incident is an upsert keyed on (order, kind), so a repeated
 * dead letter bumps the attempt count of the existing record rather than creating a duplicate.
 */
@Component
class OrderPlacedDeadLetterListener(
    private val objectMapper: ObjectMapper,
    private val orderService: OrderService,
    private val reconciliationService: ReconciliationService,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(OrderPlacedDeadLetterListener::class.java)

    @KafkaListener(
        topics = [TOPIC],
        containerFactory = "deadLetterListenerContainerFactory",
    )
    fun onDeadLetter(
        payload: String,
        @Header(KafkaHeaders.RECEIVED_KEY, required = false) recordKey: String?,
        @Header(KafkaHeaders.DLT_ORIGINAL_OFFSET, required = false) originalOffset: ByteArray?,
        @Header(KafkaHeaders.DLT_EXCEPTION_FQCN, required = false) exceptionFqcn: String?,
        @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) exceptionMessage: String?,
    ) {
        val event = try {
            objectMapper.readValue(payload, OrderPlacedEvent::class.java)
        } catch (ex: Exception) {
            // No order id means no incident can be attached to anything, so this is a poison-record
            // alert rather than a reconciliation record. The payload is never logged: it may carry
            // personal data, and the key plus the original offset are enough to locate it.
            count("poison")
            log.error(
                "Poison record on {}: key={} originalOffset={} cause={}",
                TOPIC, recordKey, originalOffset?.let { String(it) }, exceptionFqcn, ex,
            )
            return
        }

        MDC.putCloseable(CorrelationId.CONTEXT_KEY, event.correlationId).use {
            runBlocking { record(event, exceptionFqcn, exceptionMessage) }
        }
    }

    private suspend fun record(event: OrderPlacedEvent, exceptionFqcn: String?, exceptionMessage: String?) {
        val order = orderService.getOrderById(event.orderId)
        if (order == null) {
            // An incident an operator cannot redrive is alert noise, so none is opened.
            count("unknown_order")
            log.error("Dead letter for unknown order {}", event.orderId)
            return
        }

        if (order.status.isTerminal) {
            // The exhausted delivery was a duplicate of work that later succeeded. Closing here is
            // what stops permanently-open incidents accumulating for healthy orders.
            val closed = reconciliationService.closeStaleFor(event.orderId)
            count("stale_terminal")
            log.info(
                "Dead letter for order {} already terminal in {}; closed {} stale incident(s)",
                event.orderId, order.status, closed,
            )
            return
        }

        reconciliationService.recordDeliveryExhausted(
            orderId = event.orderId,
            orderState = order.status.name,
            correlationId = event.correlationId,
            failureClass = FailureClassifier.forDeadLetter(exceptionFqcn),
            detail = exceptionMessage,
        )
        count("recorded")
    }

    private fun count(result: String) {
        meterRegistry.counter("orders.events.dlt", "topic", TOPIC, "result", result).increment()
    }

    private companion object {
        /**
         * The real topic name. This version of `DeadLetterPublishingRecoverer` appends `-dlt`, not the
         * `.DLT` spelling the design docs originally used.
         */
        const val TOPIC = "order-placed-dlt"
    }
}
