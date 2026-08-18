package it.polito.cpo.payments.integration

import it.polito.cpo.contracts.events.PaymentAuthorizationEvent
import it.polito.cpo.payments.service.payment.PaymentService
import it.polito.cpo.payments.service.provider.IPaymentProvider
import it.polito.cpo.payments.service.provider.ProviderAuthorizationResult
import it.polito.cpo.payments.service.provider.ProviderRefundResult
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end proof that payments actually publishes money-path events with real infrastructure:
 * a Testcontainers Postgres (R2DBC at runtime + Flyway at startup) and an embedded Kafka broker,
 * with the full Spring context (KafkaConfiguration producer + ResilienceConfiguration beans +
 * StripeSandboxProvider wiring all loaded — only the external provider is replaced by a double).
 *
 * This is what the unit tests cannot prove: that the StringSerializer producer emits a *clean* JSON
 * string on the wire (not a double-encoded one), parseable back into the shared PaymentAuthorizationEvent
 * contract — the exact regression the technical review flagged.
 */
@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
@EmbeddedKafka(partitions = 1, topics = ["payment-authorized", "payment-declined", "payment-refunded", "payment-refund-failed"])
@Testcontainers
@Import(PaymentEventPublishingIntegrationTest.TestDoubles::class)
class PaymentEventPublishingIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}"
            }
            registry.add("spring.r2dbc.username") { postgres.username }
            registry.add("spring.r2dbc.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }
    }

    @Autowired private lateinit var paymentService: PaymentService
    @Autowired private lateinit var embeddedKafka: EmbeddedKafkaBroker

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `authorize publishes a clean JSON payment-authorized event on the wire`() {
        val consumerProps = KafkaTestUtils.consumerProps("payment-it-consumer", "true", embeddedKafka)
        consumerProps[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        val consumer = DefaultKafkaConsumerFactory(consumerProps, StringDeserializer(), StringDeserializer())
            .createConsumer()
        consumer.subscribe(listOf("payment-authorized"))

        try {
            runBlocking {
                paymentService.authorize(
                    orderId = "order-it-1",
                    principalId = "user-it",
                    amount = BigDecimal("42.00"),
                    currency = "EUR",
                    paymentMethodToken = "tok_it",
                    idempotencyKey = "key-it-1"
                )
            }

            val record = KafkaTestUtils.getSingleRecord(consumer, "payment-authorized", Duration.ofSeconds(15))
            val value = record.value()

            // The crux: raw JSON object on the wire, NOT a double-encoded quoted string.
            assertTrue(value.trimStart().startsWith("{"), "wire value must be a raw JSON object, was: $value")
            assertFalse(value.trimStart().startsWith("\""), "wire value must not be a double-encoded JSON string")

            // Parseable back into the shared contract (what a consumer such as Orders would do).
            val event = mapper.readValue(value, PaymentAuthorizationEvent::class.java)
            assertEquals("payment-authorized", event.eventType)
            assertEquals("order-it-1", event.orderId)
            assertEquals("user-it", event.principalId)
            assertEquals("AUTHORIZED", event.payload.status)
            assertEquals(0, BigDecimal("42.00").compareTo(event.payload.amount))
        } finally {
            consumer.close()
        }
    }

    @TestConfiguration
    class TestDoubles {
        // Replace the real Stripe adapter so the test never calls out; the real StripeSandboxProvider
        // bean is still constructed by the context, which validates its Resilience4j wiring.
        @Bean
        @Primary
        fun paymentProviderDouble(): IPaymentProvider = object : IPaymentProvider {
            override suspend fun authorize(amount: BigDecimal, currency: String, paymentMethodToken: String, idempotencyKey: String) =
                ProviderAuthorizationResult(success = true, providerReference = "it_ref_123")

            override suspend fun refund(providerReference: String, amount: BigDecimal?, idempotencyKey: String) =
                ProviderRefundResult(success = true, refundReference = "it_refund_123")
        }

        // Avoid any JWKS network resolution at context startup; the decoder is never exercised
        // because the test drives PaymentService directly rather than through the HTTP layer.
        @Bean
        @Primary
        fun jwtDecoderDouble(): ReactiveJwtDecoder = ReactiveJwtDecoder { Mono.empty() }
    }
}
