package it.polito.cpo.service

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.repository.OrderRepository
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the non-terminal pipeline gauges against real PostgreSQL.
 *
 * The scheduler is pushed out of the way and [PipelineStateGaugeJob.publish] is invoked directly, so
 * the assertions are deterministic rather than racing a background job.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.kafka.listener.auto-startup=false",
        "orders.pipeline.gauge-initial-delay-ms=3600000",
        "orders.pipeline.gauge-interval-ms=3600000",
    ],
)
@Testcontainers
@Import(PipelineStateGaugeJobIntegrationTest.NoRemoteJwks::class)
class PipelineStateGaugeJobIntegrationTest {

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

    @Autowired private lateinit var job: PipelineStateGaugeJob
    @Autowired private lateinit var orders: OrderRepository
    @Autowired private lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun clean() = runBlocking { orders.deleteAll() }

    @Test
    fun `a healthy system publishes explicit zeros for every tracked phase`() = runBlocking {
        job.publish()

        // A missing series and a series reading zero look identical on a graph but not to an alert.
        TRACKED.forEach { state ->
            assertEquals(0.0, inflight(state), "inflight for $state")
            assertEquals(0.0, oldestAge(state), "age for $state")
        }
    }

    @Test
    fun `each non-terminal phase is counted separately`() = runBlocking {
        seed(OrderStatus.PAYMENT_PENDING)
        seed(OrderStatus.PAYMENT_PENDING)
        seed(OrderStatus.CONFIRMING_INVENTORY)

        job.publish()

        assertEquals(2.0, inflight(OrderStatus.PAYMENT_PENDING))
        assertEquals(1.0, inflight(OrderStatus.CONFIRMING_INVENTORY))
        assertEquals(0.0, inflight(OrderStatus.COMPENSATING))
    }

    @Test
    fun `terminal orders are not counted`() = runBlocking {
        seed(OrderStatus.CONFIRMED)
        seed(OrderStatus.FAILED)
        seed(OrderStatus.CANCELLED)
        seed(OrderStatus.ACCEPTED)
        seed(OrderStatus.RESERVING)

        job.publish()

        // ACCEPTED and RESERVING are excluded on purpose: no money has moved yet, so a stall there is
        // not the failure mode these gauges exist to surface.
        TRACKED.forEach { assertEquals(0.0, inflight(it), "inflight for $it") }
    }

    @Test
    fun `the age reflects the oldest order in the phase`() = runBlocking {
        seed(OrderStatus.COMPENSATING, createdAt = LocalDateTime.now().minusMinutes(30))
        seed(OrderStatus.COMPENSATING, createdAt = LocalDateTime.now().minusMinutes(2))

        job.publish()

        val age = oldestAge(OrderStatus.COMPENSATING)
        // Around 30 minutes, and generously bounded: the point is that it tracks the OLDEST row and is
        // not offset by a time zone, which is what comparing created_at against CURRENT_TIMESTAMP
        // instead of LOCALTIMESTAMP would silently do.
        assertTrue(age > 1_700 && age < 1_900, "expected roughly 1800s, got $age")
    }

    @Test
    fun `a phase that empties falls back to zero instead of keeping its last value`() = runBlocking {
        val id = seed(OrderStatus.CONFIRMING_INVENTORY)
        job.publish()
        assertEquals(1.0, inflight(OrderStatus.CONFIRMING_INVENTORY))

        // The state disappears from the query result entirely; a per-state holder would keep
        // reporting 1 forever, which is the specific bug this test exists to catch.
        orders.deleteById(id)
        job.publish()

        assertEquals(0.0, inflight(OrderStatus.CONFIRMING_INVENTORY))
        assertEquals(0.0, oldestAge(OrderStatus.CONFIRMING_INVENTORY))
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun inflight(state: OrderStatus): Double = gauge("orders.pipeline.inflight", state)

    private fun oldestAge(state: OrderStatus): Double = gauge("orders.pipeline.oldest.age.seconds", state)

    private fun gauge(name: String, state: OrderStatus): Double {
        val gauge = meterRegistry.find(name).tag("state", state.name).gauge()
        assertNotNull(gauge, "no $name series for state $state")
        return gauge.value()
    }

    private suspend fun seed(
        status: OrderStatus,
        createdAt: LocalDateTime = LocalDateTime.now(),
    ): UUID {
        val id = UUID.randomUUID()
        orders.save(
            Order(
                id = id,
                userId = "user-gauge",
                status = status,
                routeId = "route-1",
                seats = "A1",
                travelDate = LocalDate.now().plusDays(3),
                totalAmount = BigDecimal("10.00"),
                currency = "EUR",
                createdAt = createdAt,
                recipientEmail = "user-gauge@example.com",
            ).apply { setAsNew(true) },
        )
        return id
    }

    private val TRACKED = listOf(
        OrderStatus.PAYMENT_PENDING,
        OrderStatus.CONFIRMING_INVENTORY,
        OrderStatus.COMPENSATING,
    )
}
