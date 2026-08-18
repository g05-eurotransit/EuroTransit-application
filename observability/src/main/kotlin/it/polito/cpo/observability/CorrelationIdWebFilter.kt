package it.polito.cpo.observability

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Resolves the request correlation id: accepts the inbound X-Correlation-Id header or
 * generates one, then makes it available on the response header, as an exchange
 * attribute, and in the Reactor context (from where it reaches the logging MDC).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdWebFilter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val correlationId = exchange.request.headers.getFirst(CorrelationId.HEADER)
            ?.takeIf { it.isNotBlank() }
            ?: CorrelationId.generate()

        exchange.attributes[CorrelationId.EXCHANGE_ATTRIBUTE] = correlationId
        exchange.response.headers.set(CorrelationId.HEADER, correlationId)

        return chain.filter(exchange)
            .contextWrite { context -> context.put(CorrelationId.CONTEXT_KEY, correlationId) }
    }
}
