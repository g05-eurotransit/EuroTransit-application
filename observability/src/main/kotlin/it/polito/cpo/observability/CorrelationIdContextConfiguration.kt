package it.polito.cpo.observability

import io.micrometer.context.ContextRegistry
import io.micrometer.context.ThreadLocalAccessor
import org.slf4j.MDC
import org.springframework.context.annotation.Configuration

/**
 * Bridges the Reactor context key [CorrelationId.CONTEXT_KEY] into the SLF4J MDC so that
 * every log statement emitted inside a request chain carries the correlation id.
 *
 * Requires `spring.reactor.context-propagation: auto` in the service configuration.
 */
@Configuration(proxyBeanMethods = false)
class CorrelationIdContextConfiguration {

    init {
        ContextRegistry.getInstance().registerThreadLocalAccessor(CorrelationIdThreadLocalAccessor())
    }

    class CorrelationIdThreadLocalAccessor : ThreadLocalAccessor<String> {
        override fun key(): Any = CorrelationId.CONTEXT_KEY

        override fun getValue(): String? = MDC.get(CorrelationId.CONTEXT_KEY)

        override fun setValue(value: String) {
            MDC.put(CorrelationId.CONTEXT_KEY, value)
        }

        override fun setValue() {
            MDC.remove(CorrelationId.CONTEXT_KEY)
        }
    }
}
