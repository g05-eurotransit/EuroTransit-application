package it.polito.cpo.notification

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"],
)
@EmbeddedKafka(partitions = 1, topics = ["notification-requested"])
@AutoConfigureWebTestClient
@AutoConfigureMetrics
@ActiveProfiles("test")
@Import(NotificationsApiTestConfiguration::class)
class NotificationsApiTests(
    @Autowired private val client: WebTestClient,
    @Autowired private val store: NotificationStore,
) {

    private fun seed(principal: String) = store.record(
        NotificationView(
            id = UUID.randomUUID(),
            principalId = principal,
            orderId = UUID.randomUUID(),
            message = "Your order is confirmed",
            recipientEmail = "$principal@eurotransit.local",
            occurredAt = LocalDateTime.now(),
            receivedAt = LocalDateTime.now(),
        ),
    )

    @Test
    fun `history returns only the caller's own notifications`() {
        seed("alice")
        seed("bob")

        client.get().uri("/api/notifications")
            .headers { it.setBearerAuth("alice-token") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].principalId").isEqualTo("alice")
    }

    @Test
    fun `history is empty for a principal with no notifications`() {
        client.get().uri("/api/notifications")
            .headers { it.setBearerAuth("nobody-token") }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `anonymous history request is rejected`() {
        client.get().uri("/api/notifications")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `prometheus endpoint is available to the internal scraper`() {
        client.get().uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk
    }
}

@TestConfiguration
class NotificationsApiTestConfiguration {
    @Bean
    @Primary
    fun testJwtDecoder(): ReactiveJwtDecoder =
        ReactiveJwtDecoder { token -> Mono.just(testJwt(token)) }
}

private fun testJwt(token: String): Jwt {
    val now = Instant.now()
    return Jwt.withTokenValue(token)
        .header("alg", "none")
        .subject(token.removeSuffix("-token"))
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("realm_access", mapOf("roles" to listOf("customer")))
        .build()
}
