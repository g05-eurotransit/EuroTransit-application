package it.polito.cpo.event

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.contracts.events.RefundRequestedEvent
import it.polito.cpo.model.OrderStatus
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
 * Consumes `refund-requested-dlt` and turns an exhausted refund delivery into a durable incident an
 * operator can act on. Modelled on [OrderPlacedDeadLetterListener]: same shape, same
 * poison-versus-retryable distinction, same "no write path into remote state" rule.
 *
 * **Why this exists.** Every unknown outcome in [it.polito.cpo.service.RefundOrchestrator]'s reversal
 * throws so a redelivery can converge, but the shared error handler gives up after four attempts in
 * about three seconds (`KafkaConfiguration.kafkaErrorHandler`) and the record then lands on
 * `refund-requested-dlt`. Before this listener existed, nothing consumed that topic: a routine
 * dependency blip (an Inventory pod roll takes 20-30s to become ready) was enough to strand an order
 * in `REFUNDING` — money possibly already reversed, seat still `SOLD` — with **no reconciliation
 * record at all**, because [ReconciliationService.recordSeatReturnFailure] only runs on a *definitive*
 * refusal, not on an unknown outcome that exhausted retries. Nothing was alerted and nothing could
 * retry: a fresh `POST /refund` would answer `ORDER_NOT_CONFIRMED` (the order is already `REFUNDING`)
 * and redrive would answer `NO_OPEN_RECONCILIATION_RECORD` (nothing was ever opened).
 *
 * The listener deliberately holds **no** `InventoryClient` and no `PaymentClient`: it only opens an
 * incident, exactly as the checkout dead-letter listener does. Redrive (now refund-aware — see
 * [it.polito.cpo.service.ReconciliationOperationsService.redrive]) is the operator's explicit retry.
 */
@Component
class RefundRequestedDeadLetterListener(
    private val objectMapper: ObjectMapper,
    private val orderService: OrderService,
    private val reconciliationService: ReconciliationService,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(RefundRequestedDeadLetterListener::class.java)

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
            objectMapper.readValue(payload, RefundRequestedEvent::class.java)
        } catch (ex: Exception) {
            // No order id means no incident can be attached to anything. The payload is never logged:
            // it may carry personal data, and the key plus the original offset are enough to find it.
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

    private suspend fun record(event: RefundRequestedEvent, exceptionFqcn: String?, exceptionMessage: String?) {
        val order = orderService.getOrderById(event.orderId)
        if (order == null) {
            // An incident an operator cannot redrive is alert noise, so none is opened.
            count("unknown_order")
            log.error("Dead letter for unknown order {}", event.orderId)
            return
        }

        // REFUNDED is genuinely settled for the refund's own process (unlike REFUNDING, which answers
        // `true` from OrderStatus.isTerminal but has not converged): the reversal completed despite
        // this delivery's exhaustion, so any open SEAT_RETURN_FAILURE / PAYMENT_REVERSAL_FAILURE this
        // very refund left behind is stale and safe to close here.
        if (order.status == OrderStatus.REFUNDED) {
            val closed = reconciliationService.closeStaleFor(event.orderId)
            count("stale_terminal")
            log.info(
                "Dead letter for order {} already REFUNDED; closed {} stale incident(s)",
                event.orderId, closed,
            )
            return
        }

        if (order.status != OrderStatus.REFUNDING) {
            // refund-requested is written only together with the CONFIRMED -> REFUNDING commit, in the
            // same transaction, so a well-formed event should always find the order in REFUNDING or
            // REFUNDED. Anything else means the model's own invariant does not hold; logged rather than
            // turned into an incident this consumer cannot classify.
            count("unexpected_state")
            log.error("Dead letter for order {} in unexpected state {}", event.orderId, order.status)
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
        const val TOPIC = "refund-requested-dlt"
    }
}
