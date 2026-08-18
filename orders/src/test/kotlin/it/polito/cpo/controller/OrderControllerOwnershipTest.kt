package it.polito.cpo.controller

import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.repository.IdempotentRequestRepository
import it.polito.cpo.repository.OrderRepository
import it.polito.cpo.security.SecurityConfig
import it.polito.cpo.service.CheckoutOrchestrator
import it.polito.cpo.service.OrderService
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
import org.mockito.Mockito.mock
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Verifies the ownership rule of GET /api/orders/{orderId} (api-design.md §Security Boundary):
 * a customer sees only their own order; another customer's order is 404 (not 403) so existence
 * is not revealed; back-office `operations` may read any order.
 */
@WebFluxTest(controllers = [OrderController::class])
@Import(SecurityConfig::class, OrderControllerOwnershipTest.Cfg::class)
class OrderControllerOwnershipTest @Autowired constructor(
    private val webTestClient: WebTestClient,
    private val orderService: FakeOrderService,
) {
    private val orderId = UUID.randomUUID()

    private fun seedOwnedBy(owner: String) {
        orderService.next = Order(
            id = orderId,
            userId = owner,
            status = OrderStatus.CONFIRMED,
            routeId = "R1",
            seats = "1A",
            totalAmount = BigDecimal.TEN,
            createdAt = LocalDateTime.now(),
        ).apply { setAsNew(false) }
    }

    @Test
    fun `owner can read their own order`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.userId").isEqualTo("user-owner")
    }

    @Test
    fun `another customer gets 404, not 403, for someone else's order`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("other") }
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `operations staff can read any order`() {
        seedOwnedBy("user-owner")
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("ops") }
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `a missing order is 404`() {
        orderService.next = null
        webTestClient.get().uri("/api/orders/$orderId")
            .headers { it.setBearerAuth("owner") }
            .exchange()
            .expectStatus().isNotFound
    }

    open class FakeOrderService : OrderService(
        mock(OrderRepository::class.java),
        mock(IdempotentRequestRepository::class.java),
    ) {
        var next: Order? = null
        override suspend fun getOrderById(id: UUID): Order? = next
    }

    @TestConfiguration
    class Cfg {
        @Bean
        @Primary
        fun jwtDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { token -> Mono.just(jwtFor(token)) }

        @Bean
        fun orderService(): FakeOrderService = FakeOrderService()

        @Bean
        fun checkoutOrchestrator(): CheckoutOrchestrator = mock(CheckoutOrchestrator::class.java)
    }
}

private fun jwtFor(token: String): Jwt {
    val (sub, roles) = when (token) {
        "owner" -> "user-owner" to listOf("customer")
        "other" -> "user-other" to listOf("customer")
        "ops" -> "user-ops" to listOf("operations")
        else -> "nobody" to emptyList()
    }
    val now = Instant.now()
    return Jwt.withTokenValue(token)
        .header("alg", "none")
        .subject(sub)
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("realm_access", mapOf("roles" to roles))
        .build()
}
