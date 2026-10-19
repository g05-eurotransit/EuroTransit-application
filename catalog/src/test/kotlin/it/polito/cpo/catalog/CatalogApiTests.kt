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
    fun `returns one offer by id with an evaluable refund policy`() {
        client.get().uri("/api/catalog/offers/of-002")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.offerId").isEqualTo("of-002")
            .jsonPath("$.fareClass").isEqualTo("BUSINESS")
            .jsonPath("$.refundPolicy.refundable").isEqualTo(true)
            .jsonPath("$.refundPolicy.cutoffMinutesBeforeDeparture").isEqualTo(5)
            .jsonPath("$.termsVersion").isEqualTo(1)
            // Denormalized from the route so a caller resolving one offer needs exactly one call.
            .jsonPath("$.departureTime").isEqualTo("07:00:00")
            .jsonPath("$.departureZone").isEqualTo("Europe/Rome")
    }

    @Test
    fun `returns the shared error model for an unknown offer`() {
        client.get().uri("/api/catalog/offers/nope")
            .header(CorrelationId.HEADER, "corr-off-404")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.code").isEqualTo("OFFER_NOT_FOUND")
            .jsonPath("$.correlationId").isEqualTo("corr-off-404")
    }

    @Test
    fun `route carries an IANA departure zone`() {
        // Guards the serialized form specifically: a ZoneId that came out as an object, or as a
        // fixed offset, would still typecheck but would not be usable by Orders.
        client.get().uri("/api/catalog/routes/rt-ro-vi-004")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.departureZone").isEqualTo("Europe/Rome")
    }

    @Test
    fun `conditions prose is rendered from the policy`() {
        // of-002 used to read "Refundable until departure", which the agreed rule contradicts: the
        // cutoff is five minutes before. The prose follows the policy, so it now says so.
        client.get().uri("/api/catalog/offers/of-002")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.conditions").isEqualTo("Refundable until 5 minutes before departure.")

        client.get().uri("/api/catalog/offers/of-005")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.conditions").isEqualTo("Refundable until 24 hours before departure.")

        // The Standard fare must not promise an unsupported ticket exchange.
        client.get().uri("/api/catalog/offers/of-003")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.conditions").isEqualTo("Non-refundable.")
    }

    @Test
    fun `every seeded offer matches the agreed refund table`() {
        // Table-driven on purpose: a future seed edit that drops or flips one offer's terms fails
        // here rather than reaching a customer as a refund promise the system will not honour.
        val expected = mapOf(
            "of-001" to (false to null),
            "of-002" to (true to 5),
            "of-003" to (false to null),
            "of-004" to (false to null),
            "of-005" to (true to 1440),
            "of-006" to (false to null),
        )

        expected.forEach { (offerId, terms) ->
            val (refundable, cutoff) = terms
            val body = client.get().uri("/api/catalog/offers/$offerId")
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.refundPolicy.refundable").isEqualTo(refundable)

            if (cutoff == null) {
                // The invariant that keeps the two fields from disagreeing: no cutoff without a refund.
                body.jsonPath("$.refundPolicy.cutoffMinutesBeforeDeparture").doesNotExist()
            } else {
                body.jsonPath("$.refundPolicy.cutoffMinutesBeforeDeparture").isEqualTo(cutoff)
            }
        }
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
