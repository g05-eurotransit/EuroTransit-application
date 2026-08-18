package it.polito.cpo

import it.polito.cpo.security.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * Sliced security test. Loads ONLY the real [SecurityConfig] filter chain plus stub routes,
 * not the full application — deliberately, and pre-emptively:
 *
 *  - A full-context `@SpringBootTest` would also component-scan the real NotificationController,
 *    which maps `GET /api/notifications` — the same route the stub needs — producing an
 *    "Ambiguous mapping" and a failed context. (Catalog already hit and fixed this once.)
 *
 * The stub routes are a [RouterFunction] bean, not an annotated `@RestController`: annotated
 * controllers in package `it.polito.cpo` are auto component-scanned by any full-context test in
 * this module, which would reintroduce the collision. A `@Bean` RouterFunction only exists when
 * this `@TestConfiguration` is `@Import`ed, so it never leaks. `controllers = [marker]` keeps the
 * real controller out of the `@WebFluxTest` slice too.
 */
@WebFluxTest(controllers = [NotificationsSecurityProbeMarkerController::class])
@Import(SecurityConfig::class, NotificationsSecurityTestConfiguration::class)
class NotificationsSecurityConfigTests @Autowired constructor(
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
            .headers { it.setBearerAuth("operations-token") }
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `notification APIs reject anonymous requests`() {
        webTestClient.get()
            .uri("/api/notifications")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `notification APIs accept customer role`() {
        webTestClient.get()
            .uri("/api/notifications")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `notification APIs accept operations role`() {
        webTestClient.get()
            .uri("/api/notifications")
            .headers { it.setBearerAuth("operations-token") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `notification APIs reject service tokens`() {
        webTestClient.get()
            .uri("/api/notifications")
            .headers { it.setBearerAuth("service-token") }
            .exchange()
            .expectStatus().isForbidden
    }
}

@TestConfiguration
class NotificationsSecurityTestConfiguration {
    @Bean
    @Primary
    fun testJwtDecoder(): ReactiveJwtDecoder =
        ReactiveJwtDecoder { token -> Mono.just(testJwt(token)) }

    @Bean
    fun notificationsSecurityProbeRoutes(): RouterFunction<ServerResponse> = router {
        GET("/api/notifications") { ServerResponse.ok().bodyValue(mapOf("status" to "ok")) }
        // Stands in for the actuator health endpoint, not booted in this web slice.
        GET("/actuator/health/liveness") { ServerResponse.ok().bodyValue(mapOf("status" to "UP")) }
        GET("/actuator/prometheus") { ServerResponse.ok().bodyValue("test_metric 1") }
    }
}

// Empty on purpose: gives @WebFluxTest a controller to slice around without mapping any route.
@RestController
class NotificationsSecurityProbeMarkerController

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
