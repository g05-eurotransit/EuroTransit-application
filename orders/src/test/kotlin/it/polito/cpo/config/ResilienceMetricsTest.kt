package it.polito.cpo.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResilienceMetricsTest {
    @Test fun `metrics observe actual money path registries and breaker transitions`() {
        val configuration = ResilienceConfiguration()
        val breakers = configuration.circuitBreakerRegistry()
        val bulkheads = configuration.bulkheadRegistry()
        val retries = configuration.retryRegistry()
        val meters = SimpleMeterRegistry()
        ResilienceMetrics().moneyPathResilienceMetrics(breakers, bulkheads, retries).bindTo(meters)
        val open = meters.get("resilience4j.circuitbreaker.state").tags("name","payments","state","open").gauge()
        assertEquals(0.0,open.value())
        breakers.circuitBreaker("payments").transitionToOpenState()
        assertEquals(1.0,open.value())
        breakers.circuitBreaker("payments").transitionToClosedState()
        assertEquals(0.0,open.value())
        val permits = meters.get("resilience4j.bulkhead.available.concurrent.calls").tag("name","inventory").gauge()
        val before = permits.value()
        val bulkhead = bulkheads.bulkhead("inventory")
        assertTrue(bulkhead.tryAcquirePermission())
        assertEquals(before - 1,permits.value())
        bulkhead.onComplete()
        assertEquals(before,permits.value())
        assertTrue(meters.meters.any { it.id.name.startsWith("resilience4j.retry") })
    }
    @Test fun `checkout SLI exports buckets including the existing two second objective`() {
        val meters = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        CheckoutMetrics.recordEndToEnd(meters, Duration.ofMillis(500), "confirmed")
        val exported = meters.scrape()
        assertTrue(exported.lines().any { it.startsWith("orders_checkout_e2e_duration_seconds_bucket{") && it.contains("le=\"2.0\"") && it.substringAfterLast(" ").toDoubleOrNull() == 1.0 },exported)
        assertTrue(exported.contains("orders_checkout_e2e_duration_seconds_count"))
        assertEquals(setOf("confirmed"),meters.meters.filter { it.id.name == "orders.checkout.e2e.duration" }.map { it.id.getTag("outcome") }.toSet())
    }
}
