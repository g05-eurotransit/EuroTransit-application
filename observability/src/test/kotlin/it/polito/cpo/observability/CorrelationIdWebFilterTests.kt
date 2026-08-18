package it.polito.cpo.observability

import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorrelationIdWebFilterTests {

    @RestController
    class ProbeController {
        @GetMapping("/probe")
        fun probe(exchange: ServerWebExchange): Mono<ResponseEntity<String>> =
            Mono.deferContextual { context ->
                val fromContext = context.getOrDefault(CorrelationId.CONTEXT_KEY, "missing")
                val fromAttribute = exchange.attributes[CorrelationId.EXCHANGE_ATTRIBUTE] as? String
                Mono.just(ResponseEntity.ok("$fromContext|$fromAttribute"))
            }
    }

    private val client = WebTestClient
        .bindToController(ProbeController())
        .webFilter<WebTestClient.ControllerSpec>(CorrelationIdWebFilter())
        .build()

    @Test
    fun `echoes inbound correlation id on the response`() {
        client.get().uri("/probe")
            .header(CorrelationId.HEADER, "demo-123")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(CorrelationId.HEADER, "demo-123")
            .expectBody(String::class.java).isEqualTo("demo-123|demo-123")
    }

    @Test
    fun `generates a correlation id when the header is absent`() {
        val result = client.get().uri("/probe")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()

        val generated = result.responseHeaders.getFirst(CorrelationId.HEADER)
        assertNotNull(generated)
        assertTrue(generated.isNotBlank())
        assertTrue(result.responseBody!!.startsWith(generated))
    }

    @Test
    fun `generates a correlation id when the header is blank`() {
        val result = client.get().uri("/probe")
            .header(CorrelationId.HEADER, "")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()

        val generated = result.responseHeaders.getFirst(CorrelationId.HEADER)
        assertNotNull(generated)
        assertTrue(generated.isNotBlank())
    }
}
