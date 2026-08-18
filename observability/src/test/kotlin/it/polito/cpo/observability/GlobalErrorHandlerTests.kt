package it.polito.cpo.observability

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

class GlobalErrorHandlerTests {

    @RestController
    class FailingController {
        @GetMapping("/api-exception")
        fun apiException(): Nothing =
            throw ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Route missing")

        @GetMapping("/status-exception")
        fun statusException(): Nothing =
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid business action")

        @GetMapping("/unexpected")
        fun unexpected(): Nothing = error("boom")
    }

    private val client = WebTestClient
        .bindToController(FailingController())
        .controllerAdvice(GlobalErrorHandler())
        .webFilter<WebTestClient.ControllerSpec>(CorrelationIdWebFilter())
        .build()

    @Test
    fun `maps ApiException onto the shared error model`() {
        client.get().uri("/api-exception")
            .header(CorrelationId.HEADER, "corr-1")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND")
            .jsonPath("$.message").isEqualTo("Route missing")
            .jsonPath("$.correlationId").isEqualTo("corr-1")
    }

    @Test
    fun `maps ResponseStatusException onto the shared error model`() {
        client.get().uri("/status-exception")
            .header(CorrelationId.HEADER, "corr-2")
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.message").isEqualTo("Invalid business action")
            .jsonPath("$.correlationId").isEqualTo("corr-2")
    }

    @Test
    fun `maps unexpected errors onto a 500 with the shared error model`() {
        client.get().uri("/unexpected")
            .header(CorrelationId.HEADER, "corr-3")
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.correlationId").isEqualTo("corr-3")
    }
}
