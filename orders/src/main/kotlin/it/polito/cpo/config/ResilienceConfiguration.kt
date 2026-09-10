package it.polito.cpo.config

import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.core.IntervalFunction
import it.polito.cpo.client.DefinitiveRefusal
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * Explicit Resilience4j instances for the money-path clients (inventory, payments), applied to the
 * WebClient reactive chains. Deliberately NOT `ofDefaults()`: the defaults (minimumNumberOfCalls=100,
 * 60s open state, retry-on-everything) never trip at checkout volumes and would retry business
 * declines / 4xx. Values are a starting point, tuned per dependency and meant to be adjusted after
 * load testing.
 *
 * Business refusals must not count as circuit-breaker failures. That needs saying explicitly because
 * the money-path services answer them with a 4xx, not a 200: Payments maps DECLINED to 422 and
 * IDEMPOTENCY_CONFLICT to 409, and Inventory raises 404/409 for the reservation conflicts. The
 * clients decode those bodies into a [DefinitiveRefusal], so that exception — not the raw
 * WebClientResponseException — is what the breaker would otherwise see.
 */
@Configuration
class ResilienceConfiguration {

    @Bean
    fun circuitBreakerRegistry(): CircuitBreakerRegistry {
        val registry = CircuitBreakerRegistry.ofDefaults()
        registry.circuitBreaker("inventory", circuitBreakerConfig(slowCall = Duration.ofSeconds(2)))
        registry.circuitBreaker("payments", circuitBreakerConfig(slowCall = Duration.ofSeconds(3)))
        // Browsing traffic, deliberately not the "inventory" instance. A seat map is read while
        // customers navigate, at volumes unrelated to checkout, and it queries every seat on a route
        // rather than a handful by id — so it is both more frequent and slower than any money-path
        // call. Sharing the instance would let a navigation spike trip the breaker that hold,
        // confirm and release depend on: five slow reads in a twenty-second window is enough, and
        // for the ten seconds the breaker stays open no checkout can create a hold. The threshold is
        // tighter than checkout's because a seat map has no business being slow.
        registry.circuitBreaker("inventory-read", circuitBreakerConfig(slowCall = Duration.ofSeconds(1)))
        return registry
    }

    /**
     * Note what is NOT here: there is no `inventory-read` retry, and its absence is the decision.
     *
     * A stale seat map is not worth a retry — the answer is advisory and the customer can ask again
     * by reloading — while retrying read traffic is precisely how a struggling dependency becomes an
     * outage: three attempts per call triples the load on Inventory at the moment it is least able
     * to serve it. [WebClientInventoryReadClient] therefore composes bulkhead and breaker only.
     */
    @Bean
    fun retryRegistry(): RetryRegistry {
        val registry = RetryRegistry.ofDefaults()
        registry.retry("inventory", retryConfig())
        registry.retry("payments", retryConfig())
        return registry
    }

    @Bean
    fun bulkheadRegistry(): BulkheadRegistry {
        val registry = BulkheadRegistry.ofDefaults()
        registry.bulkhead("inventory", bulkheadConfig())
        registry.bulkhead("payments", bulkheadConfig())
        // The isolation the DoD asks for ("bulkheads isolate checkout resources from unrelated
        // traffic"): saturating the seat-map pool sheds seat-map load and leaves the twenty
        // money-path permits untouched.
        registry.bulkhead("inventory-read", bulkheadConfig())
        return registry
    }

    private fun circuitBreakerConfig(slowCall: Duration): CircuitBreakerConfig =
        CircuitBreakerConfig.custom()
            // Time-based window so the breaker reacts at low checkout volumes, not after 100 calls.
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize(20) // seconds
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50f)
            .slowCallDurationThreshold(slowCall)
            .slowCallRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            // 4xx means "our request was refused on the merits", not "the dependency is down": don't
            // trip the breaker. DefinitiveRefusal is what the clients actually raise for a decoded
            // business refusal, so without it a decline storm or a run of seat conflicts would open
            // the breaker and take the money path down for a reason that is not a fault.
            .ignoreExceptions(
                DefinitiveRefusal::class.java,
                WebClientResponseException.BadRequest::class.java,
                WebClientResponseException.Conflict::class.java,
                WebClientResponseException.NotFound::class.java,
            )
            .build()

    private fun retryConfig(): RetryConfig =
        RetryConfig.custom<Any>()
            .maxAttempts(3) // 1 initial + 2 retries
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2.0, 0.5))
            // Retry only transient faults; never declines/4xx, and never CallNotPermittedException.
            .retryExceptions(
                TimeoutException::class.java,
                WebClientRequestException::class.java, // connection reset / DNS / refused
                WebClientResponseException.InternalServerError::class.java,
                WebClientResponseException.BadGateway::class.java,
                WebClientResponseException.ServiceUnavailable::class.java,
                WebClientResponseException.GatewayTimeout::class.java,
            )
            .build()

    private fun bulkheadConfig(): BulkheadConfig =
        BulkheadConfig.custom()
            .maxConcurrentCalls(20)
            .maxWaitDuration(Duration.ZERO) // fail fast when saturated rather than queueing
            .build()
}
