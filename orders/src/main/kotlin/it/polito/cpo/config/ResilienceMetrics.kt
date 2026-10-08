package it.polito.cpo.config

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Bind actual application registries explicitly; Resilience4j's Boot 3 auto-config is not Boot 4 wiring. */
@Configuration
class ResilienceMetrics {
    @Bean
    fun moneyPathResilienceMetrics(
        breakers: CircuitBreakerRegistry, bulkheads: BulkheadRegistry, retries: RetryRegistry,
    ): MeterBinder = MeterBinder { meters ->
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters)
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meters)
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters)
    }
}
