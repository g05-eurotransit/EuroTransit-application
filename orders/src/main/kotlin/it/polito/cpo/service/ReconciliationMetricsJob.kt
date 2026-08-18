package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.repository.ReconciliationRecordRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Publishes how many reconciliation incidents are open, by kind, and how old the oldest one is.
 *
 * The design doc requires an alert on *any* open incident. A counter cannot express that: once its rate
 * window passes, a record open for a week stops contributing, and the alert clears while the problem
 * remains. `orders.reconciliation.required` says an incident happened; this says one is still waiting.
 */
@Component
class ReconciliationMetricsJob(
    private val records: ReconciliationRecordRepository,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ReconciliationMetricsJob::class.java)

    private val open = MultiGauge.builder("orders.reconciliation.open")
        .description("Reconciliation incidents currently awaiting resolution")
        .register(meterRegistry)

    private val oldestAge = MultiGauge.builder("orders.reconciliation.oldest.age.seconds")
        .description("Age of the oldest open reconciliation incident")
        .baseUnit("seconds")
        .register(meterRegistry)

    @Scheduled(
        initialDelayString = "\${orders.reconciliation.metrics-initial-delay-ms:30000}",
        fixedDelayString = "\${orders.reconciliation.metrics-interval-ms:30000}",
    )
    fun publish() = runBlocking {
        try {
            // Every kind is always published so a healthy system emits explicit zeros rather than
            // gaps: a missing series and a series reading zero are indistinguishable on a graph but
            // not to an alert rule.
            open.register(
                ReconciliationKind.entries.map { kind ->
                    MultiGauge.Row.of(Tags.of("kind", kind.name), records.countOpenByKind(kind.name))
                },
                true,
            )
            // Not per kind: the alert is "something has been waiting too long", regardless of what.
            oldestAge.register(
                listOf(MultiGauge.Row.of(Tags.empty(), records.oldestOpenAgeSeconds())),
                true,
            )
        } catch (e: Exception) {
            log.error("Failed to refresh the reconciliation gauges", e)
        }
    }
}
