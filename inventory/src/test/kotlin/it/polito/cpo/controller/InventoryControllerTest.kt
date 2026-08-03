package it.polito.cpo.controller

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.polito.cpo.contracts.inventory.ReservationConfirmationRequest
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.inventory.ReservationResponse
import it.polito.cpo.contracts.inventory.ReservationStatus
import it.polito.cpo.observability.ApiException
import it.polito.cpo.observability.GlobalErrorHandler
import it.polito.cpo.repository.IdempotencyRecordRepository
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReservationRepository
import it.polito.cpo.repository.SeatRepository
import it.polito.cpo.security.EuroTransitAuthorities
import it.polito.cpo.security.SecurityConfig
import it.polito.cpo.service.ReservationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.reactive.TransactionalOperator
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@WebFluxTest(controllers = [InventoryController::class])
@Import(SecurityConfig::class, GlobalErrorHandler::class, InventoryControllerTest.ControllerTestConfig::class)
class InventoryControllerTest {

    @Autowired
    lateinit var context: ApplicationContext

    lateinit var webClient: WebTestClient

    @Autowired
    lateinit var fakeService: FakeReservationService

    @BeforeEach
    fun setUp() {
        webClient = WebTestClient
            .bindToApplicationContext(context)
            .apply(springSecurity())
            .configureClient()
            .build()

        fakeService.reset()
    }

    @TestConfiguration
    class ControllerTestConfig {
        @Bean
        @Primary
        fun fakeReservationService(): FakeReservationService {
            return FakeReservationService()
        }
    }

    class FakeReservationService : ReservationService(
        Mockito.mock(SeatRepository::class.java),
        Mockito.mock(ReservationRepository::class.java),
        Mockito.mock(IdempotencyRecordRepository::class.java),
        Mockito.mock(OutboxEventRepository::class.java),
        Mockito.mock(ObjectMapper::class.java),
        Mockito.mock(TransactionalOperator::class.java),
        SimpleMeterRegistry(),
        Clock.systemUTC()
    ) {
        var lastReserveRequest: ReservationRequest? = null
        var lastIdempotencyKey: String? = null
        var lastCorrelationId: String? = null
        var lastPrincipalId: String? = null
        
        var reserveShouldThrow: ApiException? = null
        var lastReleaseReservationId: String? = null
        var lastConfirmReservationId: String? = null
        var lastConfirmOrderId: UUID? = null
        var reserveResponseStatus: ReservationStatus = ReservationStatus.HELD

        fun reset() {
            lastReserveRequest = null
            lastIdempotencyKey = null
            lastCorrelationId = null
            lastPrincipalId = null
            reserveShouldThrow = null
            lastReleaseReservationId = null
            lastConfirmReservationId = null
            lastConfirmOrderId = null
            reserveResponseStatus = ReservationStatus.HELD
        }

        override suspend fun reserveSeats(
            idempotencyKey: String,
            principalId: String,
            correlationId: String,
            request: ReservationRequest
        ): ReservationResponse {
            if (reserveShouldThrow != null) throw reserveShouldThrow!!
            
            lastIdempotencyKey = idempotencyKey
            lastPrincipalId = principalId
            lastCorrelationId = correlationId
            lastReserveRequest = request
            
            return ReservationResponse(
                "res-123",
                reserveResponseStatus,
                LocalDateTime.now().plusMinutes(15)
            )
        }

        override suspend fun releaseReservation(reservationId: String) {
            lastReleaseReservationId = reservationId
        }

        override suspend fun confirmReservation(reservationId: String, orderId: UUID): ReservationResponse {
            lastConfirmReservationId = reservationId
            lastConfirmOrderId = orderId
            return ReservationResponse(
                reservationId,
                ReservationStatus.CONFIRMED,
                LocalDateTime.now().plusMinutes(15)
            )
        }
    }

    @Test
    fun `createReservation returns 201 Created and delegates to service with correct parameters`() {
        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val idempotencyKey = "test-idem-key"
        val correlationId = "test-corr-id"
        val userId = "user-123"

        webClient
            .mutateWith(
            mockJwt()
                .jwt { it.subject(userId) }
                .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .post()
            .uri("/api/inventory/reservations")
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Correlation-Id", correlationId)
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.reservationId").isEqualTo("res-123")
            .jsonPath("$.status").isEqualTo("HELD")

        assertNotNull(fakeService.lastReserveRequest, "The service should have been called")
        assertEquals(request.orderId, fakeService.lastReserveRequest?.orderId)
        assertEquals(idempotencyKey, fakeService.lastIdempotencyKey)
        assertEquals(correlationId, fakeService.lastCorrelationId)
        assertEquals(userId, fakeService.lastPrincipalId)
    }

    @Test
    fun `createReservation returns 409 Conflict if reservation fails due to oversell`() {
        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val idempotencyKey = "test-idem-key"
        val correlationId = "test-corr-id"
        val userId = "user-123"

        fakeService.reserveResponseStatus = ReservationStatus.FAILED

        webClient
            .mutateWith(
            mockJwt()
                .jwt { it.subject(userId) }
                .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .post()
            .uri("/api/inventory/reservations")
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Correlation-Id", correlationId)
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.reservationId").isEqualTo("res-123")
            .jsonPath("$.status").isEqualTo("FAILED")

        assertNotNull(fakeService.lastReserveRequest, "The service should have been called")
    }

    @Test
    fun `createReservation returns 400 Bad Request if Idempotency-Key header is missing`() {
        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val userId = "user-123"

        webClient
            .mutateWith(
                mockJwt()
                    .jwt { it.subject(userId) }
                    .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .post()
            .uri("/api/inventory/reservations")
            // missing Idempotency-Key
            .header("X-Correlation-Id", "test-corr-id")
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)

            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `createReservation returns 400 Bad Request if X-Correlation-Id header is missing`() {
        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val userId = "user-123"

        webClient
            .mutateWith(
                mockJwt()
                    .jwt { it.subject(userId) }
                    .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .post()
            .uri("/api/inventory/reservations")
            .header("Idempotency-Key", "test-idem-key")
            // missing X-Correlation-Id
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)

            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `createReservation returns 401 Unauthorized if JWT is missing`() {
        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")

        webClient.post()
            .uri("/api/inventory/reservations")
            .header("Idempotency-Key", "test-idem-key")
            .header("X-Correlation-Id", "test-corr-id")
            .header("X-User-Id", "user-123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `createReservation returns 403 Forbidden if JWT lacks ROLE_service authority`() {
        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val userId = "user-123"

        webClient
            .mutateWith(
                mockJwt()
                    .jwt { it.subject(userId) }
                    .authorities(SimpleGrantedAuthority("ROLE_customer")) // wrong role
            )
            .post()
            .uri("/api/inventory/reservations")
            .header("Idempotency-Key", "test-idem-key")
            .header("X-Correlation-Id", "test-corr-id")
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `createReservation returns 409 Conflict if idempotency key is already in use for a different order`() {
        val request = ReservationRequest(UUID.randomUUID(), listOf("1A", "1B"), "route-1")
        val userId = "user-123"

        fakeService.reserveShouldThrow = ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Conflict")

        webClient
            .mutateWith(
                mockJwt()
                    .jwt { it.subject(userId) }
                    .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .post()
            .uri("/api/inventory/reservations")
            .header("Idempotency-Key", "test-idem-key")
            .header("X-Correlation-Id", "test-corr-id")
            .header("X-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)

            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("IDEMPOTENCY_CONFLICT")
    }

    @Test
    fun `deleteReservation returns 204 No Content and delegates to service with correct parameters`() {
        val reservationId = "res-999"
        val userId = "user-123"

        webClient
            .mutateWith(
                mockJwt()
                    .jwt { it.subject(userId) }
                    .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .delete()
            .uri("/api/inventory/reservations/{reservationId}", reservationId)

            .exchange()
            .expectStatus().isNoContent

        assertEquals(
            reservationId,
            fakeService.lastReleaseReservationId,
            "The service should have been called"
        )
    }

    @Test
    fun `deleteReservation returns 401 Unauthorized if JWT is missing`() {
        val reservationId = "res-999"

        webClient.delete()
            .uri("/api/inventory/reservations/{reservationId}", reservationId)
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `deleteReservation returns 403 Forbidden if JWT lacks ROLE_service authority`() {
        val reservationId = "res-999"
        val userId = "user-123"

        webClient
            .mutateWith(
                mockJwt()
                    .jwt { it.subject(userId) }
                    .authorities(SimpleGrantedAuthority("ROLE_customer")) // wrong role
            )
            .delete()
            .uri("/api/inventory/reservations/{reservationId}", reservationId)
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `deleteReservation succeeds with optional X-User-Id header`() {
        val reservationId = "res-999"
        val userId = "user-123"

        webClient
            .mutateWith(
                mockJwt()
                    .jwt { it.subject(userId) }
                    .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .delete()
            .uri("/api/inventory/reservations/{reservationId}", reservationId)
            .header("X-User-Id", userId)
            .exchange()
            .expectStatus().isNoContent

        assertEquals(
            reservationId,
            fakeService.lastReleaseReservationId,
            "The service should have been called"
        )
    }

    @Test
    fun `confirmReservation returns 200 OK and delegates to service with and without X-User-Id`() {
        val reservationId = "res-123"
        val orderId = UUID.randomUUID()
        val request = ReservationConfirmationRequest(orderId = orderId)

        // Without X-User-Id
        webClient
            .mutateWith(
                mockJwt()
                    .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .put()
            .uri("/api/inventory/reservations/{reservationId}/confirmation", reservationId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reservationId").isEqualTo("res-123")
            .jsonPath("$.status").isEqualTo("CONFIRMED")

        assertEquals(reservationId, fakeService.lastConfirmReservationId)
        assertEquals(orderId, fakeService.lastConfirmOrderId)

        // With X-User-Id
        webClient
            .mutateWith(
                mockJwt()
                    .authorities(SimpleGrantedAuthority(EuroTransitAuthorities.SERVICE))
            )
            .put()
            .uri("/api/inventory/reservations/{reservationId}/confirmation", reservationId)
            .header("X-User-Id", "user-123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reservationId").isEqualTo("res-123")
            .jsonPath("$.status").isEqualTo("CONFIRMED")
    }

    @Test
    fun `confirmReservation returns 401 Unauthorized if JWT is missing`() {
        val reservationId = "res-123"
        val request = ReservationConfirmationRequest(orderId = UUID.randomUUID())

        webClient.put()
            .uri("/api/inventory/reservations/{reservationId}/confirmation", reservationId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `confirmReservation returns 403 Forbidden if JWT lacks ROLE_service authority`() {
        val reservationId = "res-123"
        val request = ReservationConfirmationRequest(orderId = UUID.randomUUID())

        webClient
            .mutateWith(
                mockJwt()
                    .authorities(SimpleGrantedAuthority("ROLE_customer"))
            )
            .put()
            .uri("/api/inventory/reservations/{reservationId}/confirmation", reservationId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isForbidden
    }
}
