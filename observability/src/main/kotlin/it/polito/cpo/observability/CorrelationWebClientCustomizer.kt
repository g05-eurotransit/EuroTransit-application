package it.polito.cpo.observability

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.boot.webclient.WebClientCustomizer
import reactor.core.publisher.Mono

/**
 * Propagates X-Correlation-Id from the Reactor context and identifies the caller with
 * X-Service-Name on every outgoing WebClient call, per the frozen internal-call contract.
 *
 * Applies to WebClient instances obtained through the auto-configured WebClient.Builder.
 */
@Component
class CorrelationWebClientCustomizer(
    @Value("\${spring.application.name}") private val serviceName: String,
) : WebClientCustomizer {

    override fun customize(builder: WebClient.Builder) {
        builder.filter(correlationFilter())
    }

    private fun correlationFilter(): ExchangeFilterFunction =
        ExchangeFilterFunction { request, next ->
            Mono.deferContextual { context ->
                val decorated = ClientRequest.from(request)
                    .headers { headers ->
                        headers.set(CorrelationId.SERVICE_NAME_HEADER, serviceName)
                        context.getOrEmpty<String>(CorrelationId.CONTEXT_KEY).ifPresent {
                            headers.set(CorrelationId.HEADER, it)
                        }
                    }
                    .build()
                next.exchange(decorated)
            }
        }
}
