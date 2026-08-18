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
 * Sliced security test. It loads ONLY the real [SecurityConfig] filter chain plus stub routes,
 * not the full application. This is deliberate:
 *
 *  - The full-context variant (@SpringBootTest) also component-scans the real CatalogController, which
 *    maps the same routes -> "Ambiguous mapping" and the context fails to load.
 *
 * The stub routes are a [RouterFunction] bean defined in [CatalogSecurityTestConfiguration], not an
 * annotated `@RestController`: annotated controllers in package `it.polito.cpo` are auto component-scanned
 * by every full-context `@SpringBootTest` in this module (same base package as `CatalogApplication`),
 * which would reintroduce the same collision against `CatalogApiTests`. A `@Bean` RouterFunction only
 * exists when this `@TestConfiguration` is explicitly `@Import`ed, so it never leaks into other tests.
 * `controllers = [CatalogSecurityProbeMarkerController::class]` restricts `@WebFluxTest`'s own controller
 * scanning to an empty marker, keeping the real CatalogController out of this slice too.
 */
@WebFluxTest(controllers = [CatalogSecurityProbeMarkerController::class])
@Import(SecurityConfig::class, CatalogSecurityTestConfiguration::class)
class CatalogSecurityConfigTests @Autowired constructor(
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
    fun `catalog reads are public`() {
        webTestClient.get()
            .uri("/api/catalog/routes")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `catalog writes are denied by default`() {
        webTestClient.post()
            .uri("/api/catalog/routes")
            .headers { it.setBearerAuth("customer-token") }
            .exchange()
            .expectStatus().isForbidden
    }
}

@TestConfiguration
class CatalogSecurityTestConfiguration {
    @Bean
    @Primary
    fun testJwtDecoder(): ReactiveJwtDecoder =
        ReactiveJwtDecoder { token -> Mono.just(testJwt(token)) }

    @Bean
    fun catalogSecurityProbeRoutes(): RouterFunction<ServerResponse> = router {
        GET("/api/catalog/routes") { ServerResponse.ok().bodyValue(mapOf("status" to "ok")) }
        POST("/api/catalog/routes") { ServerResponse.ok().bodyValue(mapOf("status" to "created")) }
        // Stands in for the actuator health endpoint, which is not booted in this web slice.
        // It lets us verify the SecurityConfig rule that /actuator/health/** is permitAll.
        GET("/actuator/health/liveness") { ServerResponse.ok().bodyValue(mapOf("status" to "UP")) }
        GET("/actuator/prometheus") { ServerResponse.ok().bodyValue("test_metric 1") }
    }
}

// Empty on purpose: gives @WebFluxTest a controller to slice around without mapping any route itself.
@RestController
class CatalogSecurityProbeMarkerController

private fun testJwt(token: String): Jwt {
    val roles = when (token) {
        "customer-token" -> listOf("customer")
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
