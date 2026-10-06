package it.polito.cpo.controller

import it.polito.cpo.observability.ApiException
import it.polito.cpo.observability.GlobalErrorHandler
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class DependencyErrorHandlerTest {
    @RestController
    class Failures {
        @GetMapping("/db") fun db(): Nothing = throw DataAccessResourceFailureException("pool acquisition timeout")
        @GetMapping("/overload") fun overload(): Nothing = throw ApiException(HttpStatus.TOO_MANY_REQUESTS,"CHECKOUT_OVERLOADED","busy")
        @GetMapping("/bug") fun bug(): Nothing = error("bug")
    }
    private val client = WebTestClient.bindToController(Failures()).controllerAdvice(DependencyErrorHandler(),GlobalErrorHandler()).build()
    @Test fun `database resource failure is 503 without retry after`() {
        client.get().uri("/db").exchange().expectStatus().isEqualTo(503).expectHeader().doesNotExist("Retry-After").expectBody().jsonPath("$.code").isEqualTo("CHECKOUT_UNAVAILABLE")
    }
    @Test fun `controlled overload is 429 with bounded retry hint`() {
        client.get().uri("/overload").exchange().expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After","1")
    }
    @Test fun `unexpected defects remain 500`() {
        client.get().uri("/bug").exchange().expectStatus().isEqualTo(500)
    }
}
