package it.polito.cpo.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import it.polito.cpo.model.OrderStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes how many orders sit in each non-terminal checkout phase, and how old the oldest one is.
 *
 * These are the observability counterpart of the phases the synchronous Inventory confirmation
 * introduces, plus `REFUNDING` (ADR-015): an order stranded in any of them has money committed or
 * reversed somewhere and has not converged, and unlike a failure it produces no event of its own —
 * nothing else in the system would ever mention it. A counter cannot express "one has been stuck
 * for an hour" once its rate window has passed, so the gauge is required rather than a nicety
 * (docs/design/reservation-confirmation.md, Observability Requirements).
 */
@Component
class PipelineStateGaugeJob(
    private val orderService: OrderService,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(PipelineStateGaugeJob::class.java)

    // MultiGauge rather than one AtomicLong per state: when a state empties it disappears from the
    // query result, and a per-state holder would keep reporting its last value forever.
    private val inflight = MultiGauge.builder("orders.pipeline.inflight")
        .description("Orders currently in a non-terminal checkout phase")
        .register(meterRegistry)

    // ADR-016. How many customer-hold orders sit past their T1 deadline and how far the oldest is past
    // it: the T1 expiry job's lag. Separate from the two gauges above on purpose — HELD_AWAITING_PAYMENT
    // is a customer reading a page, not a stuck pipeline, and must never feed the checkout-stuck alert,
    // which takes the max of `oldest.age.seconds` over every state.
    private val overdueHolds = AtomicLong(0)
    private val overdueOldest = AtomicReference(0.0)

    init {
        Gauge.builder("orders.holds.overdue", overdueHolds) { it.get().toDouble() }
            .description("Customer-hold orders still waiting after their payment deadline")
            .register(meterRegistry)
        Gauge.builder("orders.holds.overdue.oldest.seconds", overdueOldest) { it.get() }
            .description("How far the oldest overdue customer hold is past its deadline")
            .baseUnit("seconds")
            .register(meterRegistry)
    }

    private val oldestAge = MultiGauge.builder("orders.pipeline.oldest.age.seconds")
        .description("Age of the oldest order in each non-terminal checkout phase")
        .baseUnit("seconds")
        .register(meterRegistry)

    @Scheduled(
        initialDelayString = "\${orders.pipeline.gauge-initial-delay-ms:30000}",
        fixedDelayString = "\${orders.pipeline.gauge-interval-ms:30000}",
    )
    fun publish() = runBlocking {
        try {
            val byState = orderService.countNonTerminalByState().toList().associateBy { it.status }

            // Every tracked state is always published, so a healthy system emits explicit zeros
            // instead of gaps. A missing series and a series reading zero look the same on a graph
            // but not to an alert rule.
            inflight.register(
                (TRACKED + OrderStatus.HELD_AWAITING_PAYMENT).map { state ->
                    MultiGauge.Row.of(Tags.of("state", state.name), byState[state.name]?.orderCount ?: 0L)
                },
                true,
            )
            oldestAge.register(
                TRACKED.map { state ->
                    MultiGauge.Row.of(Tags.of("state", state.name), byState[state.name]?.oldestAgeSeconds ?: 0.0)
                },
                true,
            )

            val overdue = orderService.countOverdueHolds()
            overdueHolds.set(overdue.orderCount)
            overdueOldest.set(overdue.oldestAgeSeconds)
        } catch (e: Exception) {
            // A gauge refresh must never take the service down; the previous values simply persist
            // until the next run.
            log.error("Failed to refresh the checkout pipeline gauges", e)
        }
    }

    private companion object {
        val TRACKED = listOf(
            OrderStatus.PAYMENT_PENDING,
            OrderStatus.CONFIRMING_INVENTORY,
            OrderStatus.COMPENSATING,
            OrderStatus.REFUNDING,
        )
    }
}
