package it.polito.cpo.catalog

import it.polito.cpo.observability.CorrelationId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureMetrics
@ActiveProfiles("test")
class CatalogApiTests(@Autowired private val client: WebTestClient) {

    @Test
    fun `lists seeded routes`() {
        client.get().uri("/api/catalog/routes")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].routeId").isEqualTo("rt-to-mi-001")
            .jsonPath("$[0].origin").isEqualTo("Torino Porta Nuova")
    }

    @Test
    fun `returns one route by id`() {
        client.get().uri("/api/catalog/routes/rt-mi-pa-002")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.routeId").isEqualTo("rt-mi-pa-002")
            .jsonPath("$.destination").isEqualTo("Paris Gare de Lyon")
    }

    @Test
    fun `returns the shared error model for an unknown route`() {
        client.get().uri("/api/catalog/routes/nope")
            .header(CorrelationId.HEADER, "corr-cat-404")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND")
            .jsonPath("$.correlationId").isEqualTo("corr-cat-404")
    }

    @Test
    fun `lists seeded offers`() {
        client.get().uri("/api/catalog/offers")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].offerId").isEqualTo("of-001")
            .jsonPath("$[0].currency").isEqualTo("EUR")
    }

    @Test
    fun `echoes an inbound correlation id`() {
        client.get().uri("/api/catalog/routes")
            .header(CorrelationId.HEADER, "demo-123")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(CorrelationId.HEADER, "demo-123")
    }

    @Test
    fun `generates a correlation id when absent`() {
        val result = client.get().uri("/api/catalog/routes")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()

        val generated = assertNotNull(result.responseHeaders.getFirst(CorrelationId.HEADER))
        assertTrue(generated.isNotBlank())
    }

    @Test
    fun `readiness and liveness probes are up`() {
        client.get().uri("/actuator/health/readiness")
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("UP")

        client.get().uri("/actuator/health/liveness")
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("UP")
    }

    @Test
    fun `prometheus endpoint is available to the internal scraper`() {
        client.get().uri("/api/catalog/routes").exchange().expectStatus().isOk

        client.get().uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk
    }
}
