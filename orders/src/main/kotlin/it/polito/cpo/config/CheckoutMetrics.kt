package it.polito.cpo.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/** Only the two terminal outcomes are allowed; order/run IDs never become metric labels. */
object CheckoutMetrics {
    fun recordEndToEnd(meters: MeterRegistry, elapsed: Duration, outcome: String) {
        require(outcome == "confirmed" || outcome == "failed")
        if (elapsed.isNegative) return
        Timer.builder("orders.checkout.e2e.duration")
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMillis(1))
            .maximumExpectedValue(Duration.ofMinutes(5))
            .serviceLevelObjectives(Duration.ofSeconds(2))
            .register(meters)
            .record(elapsed)
    }
}
