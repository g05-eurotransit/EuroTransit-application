package it.polito.cpo

import it.polito.cpo.security.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * Sliced security test. It loads ONLY the real [SecurityConfig] filter chain plus a stub controller,
 * not the full application. This is deliberate:
 *
 *  - The full-context variant (@SpringBootTest) also component-scans the real OrderController, which
 *    maps the same routes as the stub -> "Ambiguous mapping" and the context fails to load.
 *  - A full context would also boot Flyway/R2DBC/Kafka, requiring a database that CI does not have.
 *
 * `controllers = [OrdersSecurityProbeController]` excludes the real controller from the slice, and the
 * stub returns canned responses so we assert the SECURITY rules in isolation (not business behaviour:
 * the real controller returns 202 and requires a UUID path, which is orthogonal to these checks).
 *
 * The stub controller and test configuration are NESTED classes on purpose: as top-level classes in
 * the `it.polito.cpo` package they would be component-scanned by any full `@SpringBootTest` in this
 * module and clash with the real OrderController (see CheckoutPipelineIntegrationTest).
 */
@WebFluxTest(controllers = [OrdersSecurityConfigTests.OrdersSecurityProbeController::class])
@Import(SecurityConfig::class, OrdersSecurityConfigTests.OrdersSecurityTestConfiguration::class)
class OrdersSecurityConfigTests @Autowired constructor(
    private val webTestClient: WebTestClient,
) {
    @Test
    fun `health endpoint is public`() {
        webTestClient.get()
            .uri("/actuator/health/liveness")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `prometheus endpoint permits an unauthenticated internal scrape`() {
        webTestClient.get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `other actuator endpoints remain denied`() {
        webTestClient.get()
            .uri("/actuator/env")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `order creation rejects anonymous requests`() {
        webTestClient.post()
            .uri("/api/orders")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `order creation accepts customer role`() {
        webTestClient.post()
            .uri("/api/orders")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `order reads accept operations role for future support access`() {
        webTestClient.get()
            .uri("/api/orders/order-123")
            .headers { it.setBearerAuth("operations-token") }
            .exchange()
            .expectStatus().isOk
    }

    // The collection path, not a sub-resource. The GET rule in SecurityConfig is written against the
    // `/api/orders` prefix with a trailing double wildcard, and a PathPattern double wildcard is
    // documented to match zero trailing segments — but "documented" is not "asserted". If that ever
    // stopped holding, `anyExchange().denyAll()` would make the customer order history unreachable
    // for everyone, rather than failing loudly here.
    //
    // (Line comments, not KDoc: Kotlin block comments nest, so a literal path pattern containing a
    // slash-star sequence would open a nested comment that never closes.)
    @Test
    fun `the order list is reachable for the customer role`() {
        webTestClient.get()
            .uri("/api/orders")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `the order list rejects anonymous requests`() {
        webTestClient.get()
            .uri("/api/orders")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `order creation rejects tokens without the customer role`() {
        webTestClient.post()
            .uri("/api/orders")
            .headers { it.setBearerAuth("operations-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `order cancellation is reachable for the customer role`() {
        // Regression guard: with no matching rule, anyExchange().denyAll() made this endpoint
        // unreachable for everyone, which also made the cancellation-race behaviour untestable.
        webTestClient.post()
            .uri("/api/orders/order-123/cancel")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `order cancellation is reachable for the operations role`() {
        webTestClient.post()
            .uri("/api/orders/order-123/cancel")
            .headers { it.setBearerAuth("operations-token") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `order cancellation rejects anonymous requests`() {
        webTestClient.post()
            .uri("/api/orders/order-123/cancel")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `order cancellation rejects a service credential`() {
        // A service token drives internal calls; it must not be able to cancel a customer's order.
        webTestClient.post()
            .uri("/api/orders/order-123/cancel")
            .headers { it.setBearerAuth("service-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `reconciliation redrive accepts only the operations role`() {
        webTestClient.post()
            .uri("/api/orders/order-123/reconciliation/redrive")
            .headers { it.setBearerAuth("operations-token") }
            .exchange()
            .expectStatus().isOk

        webTestClient.post()
            .uri("/api/orders/order-123/reconciliation/redrive")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `reconciliation redrive rejects a service credential`() {
        // The service credential drives every internal money-path call. If it could redrive an
        // incident, the operator API would not be an operator API.
        webTestClient.post()
            .uri("/api/orders/order-123/reconciliation/redrive")
            .headers { it.setBearerAuth("service-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `reconciliation resolve accepts only the operations role`() {
        webTestClient.post()
            .uri("/api/orders/order-123/reconciliation/rec-1/resolve")
            .headers { it.setBearerAuth("operations-token") }
            .exchange()
            .expectStatus().isOk

        webTestClient.post()
            .uri("/api/orders/order-123/reconciliation/rec-1/resolve")
            .headers { it.setBearerAuth("service-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `reconciliation endpoints reject anonymous requests`() {
        webTestClient.post()
            .uri("/api/orders/order-123/reconciliation/redrive")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `a customer read of the reconciliation path is not silently widened by the POST rules`() {
        // The POST rules use single-segment wildcards; a GET on the same path must still fall through
        // to the order-read rule rather than to the operator rules.
        webTestClient.get()
            .uri("/api/orders/order-123/reconciliation/redrive")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `unlisted paths are denied even for authenticated users`() {
        webTestClient.get()
            .uri("/internal/debug")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @TestConfiguration
    class OrdersSecurityTestConfiguration {
        @Bean
        @Primary
        fun testJwtDecoder(): ReactiveJwtDecoder =
            ReactiveJwtDecoder { token -> Mono.just(testJwt(token)) }

        @Bean
        fun ordersSecurityProbeController(): OrdersSecurityProbeController =
            OrdersSecurityProbeController()
    }

    @RestController
    class OrdersSecurityProbeController {
        @PostMapping("/api/orders")
        fun createOrder(): Map<String, String> = mapOf("status" to "accepted")

        @GetMapping("/api/orders")
        fun listOrders(): Map<String, String> = mapOf("orders" to "[]")

        @GetMapping("/api/orders/{orderId}")
        fun readOrder(@PathVariable orderId: String): Map<String, String> = mapOf("orderId" to orderId)

        @PostMapping("/api/orders/{orderId}/cancel")
        fun cancelOrder(@PathVariable orderId: String): Map<String, String> = mapOf("orderId" to orderId)

        @PostMapping("/api/orders/{orderId}/reconciliation/redrive")
        fun redrive(@PathVariable orderId: String): Map<String, String> = mapOf("orderId" to orderId)

        @PostMapping("/api/orders/{orderId}/reconciliation/{recordId}/resolve")
        fun resolve(
            @PathVariable orderId: String,
            @PathVariable recordId: String,
        ): Map<String, String> = mapOf("orderId" to orderId, "recordId" to recordId)

        // Stands in for the order-read route on the same path prefix, so the test can prove the POST
        // operator rules did not widen GET access.
        @GetMapping("/api/orders/{orderId}/reconciliation/{action}")
        fun readReconciliation(
            @PathVariable orderId: String,
            @PathVariable action: String,
        ): Map<String, String> = mapOf("orderId" to orderId, "action" to action)

        // Stands in for the actuator health endpoint, which is not booted in this web slice.
        // It lets us verify the SecurityConfig rule that /actuator/health/** is permitAll.
        @GetMapping("/actuator/health/liveness")
        fun liveness(): Map<String, String> = mapOf("status" to "UP")

        @GetMapping("/actuator/prometheus")
        fun prometheus(): String = "test_metric 1"
    }
}

private fun testJwt(token: String): Jwt {
    val roles = when (token) {
        "customer-token" -> listOf("customer")
        "operations-token" -> listOf("operations")
        "service-token" -> listOf("service")
        else -> emptyList()
    }
    val now = Instant.now()

    return Jwt.withTokenValue(token)
        .header("alg", "none")
        .subject(token.removeSuffix("-token"))
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("realm_access", mapOf("roles" to roles))
        .build()
}
