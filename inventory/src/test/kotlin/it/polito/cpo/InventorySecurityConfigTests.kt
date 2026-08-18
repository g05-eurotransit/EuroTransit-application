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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * Sliced security test. Loads ONLY the real [SecurityConfig] filter chain plus a stub controller,
 * not the full application. This keeps the test independent of the database (Flyway/R2DBC), Kafka,
 * and any future startup wiring the inventory service may add — so it stays green in CI without a
 * broker or Postgres. Mirrors the orders security slice.
 */
@WebFluxTest(controllers = [InventorySecurityProbeController::class])
@Import(SecurityConfig::class, InventorySecurityTestConfiguration::class)
class InventorySecurityConfigTests @Autowired constructor(
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
            .headers { it.setBearerAuth("service-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `inventory APIs reject anonymous requests`() {
        webTestClient.post()
            .uri("/api/inventory/security-probe")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `inventory APIs require service role`() {
        webTestClient.post()
            .uri("/api/inventory/security-probe")
            .headers { it.setBearerAuth("service-token") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `inventory APIs reject customer tokens`() {
        webTestClient.post()
            .uri("/api/inventory/security-probe")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isForbidden
    }
}

@TestConfiguration
class InventorySecurityTestConfiguration {
    @Bean
    @Primary
    fun testJwtDecoder(): ReactiveJwtDecoder =
        ReactiveJwtDecoder { token -> Mono.just(testJwt(token)) }

    @Bean
    fun inventorySecurityProbeController(): InventorySecurityProbeController =
        InventorySecurityProbeController()
}

@RestController
class InventorySecurityProbeController {
    @PostMapping("/api/inventory/security-probe")
    fun reserve(): Map<String, String> = mapOf("status" to "reserved")

    // Stands in for the actuator health endpoint, which is not booted in this web slice.
    // Lets us verify the SecurityConfig rule that /actuator/health/** is permitAll.
    @GetMapping("/actuator/health/liveness")
    fun liveness(): Map<String, String> = mapOf("status" to "UP")

    @GetMapping("/actuator/prometheus")
    fun prometheus(): String = "test_metric 1"
}

private fun testJwt(token: String): Jwt {
    val roles = when (token) {
        "customer-token" -> listOf("customer")
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
