package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.repository.ReconciliationRecordRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the reconciliation gauges against real PostgreSQL.
 *
 * The age gauge is registered with empty tags, which is unusual enough to be worth asserting: if that
 * produced no series, `ReconciliationAgeHigh` would never fire and nothing would say so. The whole
 * point of these gauges is to be the only signal for an incident nobody else reports.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.kafka.listener.auto-startup=false",
        "orders.reconciliation.metrics-initial-delay-ms=3600000",
        "orders.reconciliation.metrics-interval-ms=3600000",
    ],
)
@Testcontainers
@Import(ReconciliationMetricsJobIntegrationTest.NoRemoteJwks::class)
class ReconciliationMetricsJobIntegrationTest {

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

    @TestConfiguration
    class NoRemoteJwks {
        @Bean
        @Primary
        fun testJwtDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { Mono.empty() }
    }

    @Autowired private lateinit var job: ReconciliationMetricsJob
    @Autowired private lateinit var records: ReconciliationRecordRepository
    @Autowired private lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun clean() = runBlocking { records.deleteAll() }

    @Test
    fun `a healthy system publishes explicit zeros for every kind`() = runBlocking {
        job.publish()

        ReconciliationKind.entries.forEach { kind ->
            assertEquals(0.0, openGauge(kind), "open for $kind")
        }
        assertEquals(0.0, ageGauge())
    }

    @Test
    fun `each kind is counted separately`() = runBlocking {
        open(UUID.randomUUID(), ReconciliationKind.INVARIANT_VIOLATION)
        open(UUID.randomUUID(), ReconciliationKind.INVARIANT_VIOLATION)
        open(UUID.randomUUID(), ReconciliationKind.PAYMENT_REVERSAL_FAILURE)

        job.publish()

        assertEquals(2.0, openGauge(ReconciliationKind.INVARIANT_VIOLATION))
        assertEquals(1.0, openGauge(ReconciliationKind.PAYMENT_REVERSAL_FAILURE))
        assertEquals(0.0, openGauge(ReconciliationKind.DELIVERY_EXHAUSTED))
    }

    @Test
    fun `the age gauge is published as a usable series`() = runBlocking {
        open(UUID.randomUUID(), ReconciliationKind.DELIVERY_EXHAUSTED)

        job.publish()

        // Registered with empty tags. If that produced no series, ReconciliationAgeHigh would never
        // fire and the failure would be invisible.
        val gauge = meterRegistry.find("orders.reconciliation.oldest.age.seconds").gauge()
        assertNotNull(gauge, "the age gauge produced no series")
        assertTrue(gauge.value() >= 0.0)
    }

    @Test
    fun `resolving the last incident returns the gauges to zero`() = runBlocking {
        val orderId = UUID.randomUUID()
        open(orderId, ReconciliationKind.DELIVERY_EXHAUSTED)
        job.publish()
        assertEquals(1.0, openGauge(ReconciliationKind.DELIVERY_EXHAUSTED))

        records.closeAutoResolvable(orderId)
        job.publish()

        assertEquals(0.0, openGauge(ReconciliationKind.DELIVERY_EXHAUSTED))
        assertEquals(0.0, ageGauge())
    }

    private fun openGauge(kind: ReconciliationKind): Double {
        val gauge = meterRegistry.find("orders.reconciliation.open").tag("kind", kind.name).gauge()
        assertNotNull(gauge, "no open-incident series for $kind")
        return gauge.value()
    }

    private fun ageGauge(): Double {
        val gauge = meterRegistry.find("orders.reconciliation.oldest.age.seconds").gauge()
        assertNotNull(gauge, "no age series")
        return gauge.value()
    }

    private suspend fun open(orderId: UUID, kind: ReconciliationKind) =
        records.upsertOpen(
            id = UUID.randomUUID(),
            orderId = orderId,
            kind = kind.name,
            orderState = OrderStatus.COMPENSATING.name,
            correlationId = "corr-1",
            failureClass = "dependency_error",
            failureDetail = null,
        )
}
