package it.polito.cpo.payments.controllers

import it.polito.cpo.PaymentsSecurityTestConfiguration
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.contracts.payments.PaymentResponse
import it.polito.cpo.contracts.payments.PaymentStatus
import it.polito.cpo.payments.service.payment.IPaymentService
import it.polito.cpo.security.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal
import java.util.UUID
import org.mockito.Mockito

@WebFluxTest(controllers = [PaymentController::class])
@Import(SecurityConfig::class, PaymentsSecurityTestConfiguration::class, PaymentControllerTestConfig::class)
class PaymentControllerTest @Autowired constructor(
    private val webTestClient: WebTestClient,
    private val paymentService: FakeIPaymentService
) {
    @Test
    fun `authorize returns 200 OK on success`() {
        val request = PaymentRequest(
            orderId = UUID.randomUUID(),
            principalId = "user1",
            amount = BigDecimal.TEN,
            currency = "EUR",
            paymentMethodToken = "tok_123"
        )
        
        paymentService.nextResponse = PaymentResponse(
            status = PaymentStatus.AUTHORIZED,
            providerReference = "ch_123"
        )

        webTestClient.post()
            .uri("/api/payments/authorize")
            .header("Idempotency-Key", "idem-key-1")
            .headers { it.setBearerAuth("service-token") }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("AUTHORIZED")
            .jsonPath("$.providerReference").isEqualTo("ch_123")
    }

    @Test
    fun `authorize returns 422 UNPROCESSABLE ENTITY on declined payment`() {
        val request = PaymentRequest(
            orderId = UUID.randomUUID(),
            principalId = "user1",
            amount = BigDecimal.TEN,
            currency = "EUR",
            paymentMethodToken = "tok_123"
        )
        
        paymentService.nextResponse = PaymentResponse(
            status = PaymentStatus.DECLINED,
            providerReference = null,
            errorCode = "card_declined"
        )

        webTestClient.post()
            .uri("/api/payments/authorize")
            .header("Idempotency-Key", "idem-key-2")
            .headers { it.setBearerAuth("service-token") }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
            .expectBody()
            .jsonPath("$.status").isEqualTo("DECLINED")
    }
    @Test
    fun `authorize returns 409 CONFLICT on idempotency conflict`() {
        val request = PaymentRequest(
            orderId = UUID.randomUUID(),
            principalId = "user1",
            amount = BigDecimal.TEN,
            currency = "EUR",
            paymentMethodToken = "tok_123"
        )
        
        paymentService.nextResponse = PaymentResponse(
            status = PaymentStatus.CONFLICT,
            providerReference = null,
            errorCode = "IDEMPOTENCY_CONFLICT"
        )

        webTestClient.post()
            .uri("/api/payments/authorize")
            .header("Idempotency-Key", "idem-key-conflict")
            .headers { it.setBearerAuth("service-token") }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.status").isEqualTo("CONFLICT")
            .jsonPath("$.errorCode").isEqualTo("IDEMPOTENCY_CONFLICT")
    }

    @Test
    fun `refund returns 200 OK on success`() {
        val request = it.polito.cpo.payments.dto.PaymentRefundRequest(
            orderId = "order-123",
            amount = BigDecimal.TEN
        )
        
        paymentService.nextRefundResponse = it.polito.cpo.payments.dto.PaymentRefundResponse(
            status = IPaymentService.RefundStatus.REFUNDED,
            refundReference = "re_123",
            errorCode = null
        )

        webTestClient.post()
            .uri("/api/payments/refund")
            .header("Idempotency-Key", "idem-refund")
            .headers { it.setBearerAuth("service-token") }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("REFUNDED")
            .jsonPath("$.refundReference").isEqualTo("re_123")
    }
}

class FakeIPaymentService : IPaymentService {
    var nextResponse = PaymentResponse(PaymentStatus.AUTHORIZED, "ref")
    var nextRefundResponse = it.polito.cpo.payments.dto.PaymentRefundResponse(IPaymentService.RefundStatus.REFUNDED, "ref", null)
    
    override suspend fun authorize(orderId: String, principalId: String, amount: BigDecimal, currency: String, paymentMethodToken: String, idempotencyKey: String) = nextResponse
    override suspend fun refund(orderId: String, amount: BigDecimal?, idempotencyKey: String) = nextRefundResponse
}

@TestConfiguration
class PaymentControllerTestConfig {
    @Bean
    @Primary
    fun paymentServiceMock(): FakeIPaymentService = FakeIPaymentService()
}
