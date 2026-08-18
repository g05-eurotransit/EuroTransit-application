package it.polito.cpo.repository

import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertNull

/**
 * Proves the reconciliation record semantics against real PostgreSQL, because they live in SQL rather
 * than in Kotlin: the partial unique index on `(order_id, kind) WHERE status = 'OPEN'` is what turns
 * "a repeated incident updates the existing record" into an `ON CONFLICT` target instead of a
 * read-then-write race, and no in-memory fake can stand in for that.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.kafka.listener.auto-startup=false"],
)
@Testcontainers
@Import(ReconciliationRecordRepositoryIntegrationTest.NoRemoteJwks::class)
class ReconciliationRecordRepositoryIntegrationTest {

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

    @Autowired private lateinit var records: ReconciliationRecordRepository

    /**
     * The gauge queries are intentionally global (they feed an "any open incident" alert), so leftovers
     * from a sibling test would make them non-deterministic. Truncate rather than scope the queries.
     */
    @BeforeEach
    fun clean() = runBlocking { records.deleteAll() }

    @Test
    fun `opening an incident inserts one row`() = runBlocking {
        val orderId = UUID.randomUUID()

        val record = open(orderId, ReconciliationKind.INVARIANT_VIOLATION, "reservation_order_conflict")

        assertEquals(orderId, record.orderId)
        assertEquals(1, record.attemptCount)
        assertEquals(ReconciliationStatus.OPEN.name, record.status)
        assertEquals(1, openFor(orderId).size)
    }

    @Test
    fun `a repeated incident updates the existing row instead of duplicating it`() = runBlocking {
        val orderId = UUID.randomUUID()

        open(orderId, ReconciliationKind.PAYMENT_REVERSAL_FAILURE, "payment_reversal_failed")
        val second = open(orderId, ReconciliationKind.PAYMENT_REVERSAL_FAILURE, "payment_reversal_failed")

        assertEquals(2, second.attemptCount)
        assertEquals(1, openFor(orderId).size)
    }

    @Test
    fun `two concurrent writers converge on a single row`() = runBlocking {
        val orderId = UUID.randomUUID()

        // Proves ON CONFLICT beats a read-then-write race: a SELECT-then-INSERT would produce two rows.
        coroutineScope {
            listOf(
                async(Dispatchers.IO) { open(orderId, ReconciliationKind.DELIVERY_EXHAUSTED, "dependency_error") },
                async(Dispatchers.IO) { open(orderId, ReconciliationKind.DELIVERY_EXHAUSTED, "dependency_error") },
            ).awaitAll()
        }

        val open = openFor(orderId)
        assertEquals(1, open.size)
        assertEquals(2, open.single().attemptCount)
    }

    @Test
    fun `different kinds for the same order coexist`() = runBlocking {
        val orderId = UUID.randomUUID()

        open(orderId, ReconciliationKind.INVARIANT_VIOLATION, "reservation_seat_conflict")
        open(orderId, ReconciliationKind.PAYMENT_REVERSAL_FAILURE, "payment_reversal_failed")

        assertEquals(2, openFor(orderId).size)
    }

    @Test
    fun `the terminal close resolves the automatic kinds and leaves an invariant violation open`() = runBlocking {
        val orderId = UUID.randomUUID()
        open(orderId, ReconciliationKind.DELIVERY_EXHAUSTED, "dependency_error")
        open(orderId, ReconciliationKind.PAYMENT_REVERSAL_FAILURE, "payment_reversal_failed")
        open(orderId, ReconciliationKind.INVARIANT_VIOLATION, "reservation_not_found")

        val closed = records.closeAutoResolvable(orderId)

        assertEquals(2, closed)
        val stillOpen = openFor(orderId)
        assertEquals(1, stillOpen.size)
        // An operator, not a terminal state, is what resolves an impossible state.
        assertEquals(ReconciliationKind.INVARIANT_VIOLATION.name, stillOpen.single().kind)
    }

    @Test
    fun `a resolved incident does not block a new one for the same order and kind`() = runBlocking {
        val orderId = UUID.randomUUID()
        open(orderId, ReconciliationKind.DELIVERY_EXHAUSTED, "dependency_error")
        records.closeAutoResolvable(orderId)

        // The index is partial on purpose: a later failure is a NEW incident, not a resurrection.
        val reopened = open(orderId, ReconciliationKind.DELIVERY_EXHAUSTED, "dependency_error")

        assertEquals(1, reopened.attemptCount)
        assertEquals(1, openFor(orderId).size)
        // Two distinct incidents now exist for the same (order, kind): one resolved, one open.
        assertEquals(2, records.findAll().toList().count { it.orderId == orderId })
    }

    @Test
    fun `operator resolution only ever touches an open invariant violation`() = runBlocking {
        val orderId = UUID.randomUUID()
        val violation = open(orderId, ReconciliationKind.INVARIANT_VIOLATION, "reservation_order_conflict")
        val reversal = open(orderId, ReconciliationKind.PAYMENT_REVERSAL_FAILURE, "payment_reversal_failed")

        // Wrong kind: the SQL filter is what makes "cannot waive a pending reversal" enforceable.
        assertEquals(0, records.resolveInvariantViolation(reversal.id, orderId, "ops-1", "INC-1"))
        // Wrong order: an id from another order must not be resolvable through this order's path.
        assertEquals(0, records.resolveInvariantViolation(violation.id, UUID.randomUUID(), "ops-1", "INC-1"))

        assertEquals(1, records.resolveInvariantViolation(violation.id, orderId, "ops-1", "INC-1"))
        val resolved = records.findById(violation.id)
        assertNotNull(resolved)
        assertEquals(ReconciliationStatus.RESOLVED.name, resolved.status)
        assertEquals("INC-1", resolved.evidenceReference)
        assertNotNull(resolved.resolvedAt)

        // Already resolved: repeating it must not reopen or rewrite the evidence.
        assertEquals(0, records.resolveInvariantViolation(violation.id, orderId, "ops-2", "INC-2"))
    }

    @Test
    fun `a redrive bumps the attempt count of every open incident`() = runBlocking {
        val orderId = UUID.randomUUID()
        open(orderId, ReconciliationKind.DELIVERY_EXHAUSTED, "dependency_error")
        open(orderId, ReconciliationKind.INVARIANT_VIOLATION, "reservation_not_found")

        assertEquals(2, records.touchRedrive(orderId, "corr-redrive"))

        openFor(orderId).forEach {
            assertEquals(2, it.attemptCount)
            assertEquals("corr-redrive", it.correlationId)
        }
    }

    @Test
    fun `the gauges report open incidents and their age`() = runBlocking {
        val orderId = UUID.randomUUID()
        open(orderId, ReconciliationKind.INVARIANT_VIOLATION, "reservation_seat_conflict")

        assertEquals(1, records.countOpenByKind(ReconciliationKind.INVARIANT_VIOLATION.name))
        assertEquals(0, records.countOpenByKind(ReconciliationKind.DELIVERY_EXHAUSTED.name))
        // Non-negative and finite: the value itself is timing-dependent, its presence is not.
        assert(records.oldestOpenAgeSeconds() >= 0.0)
    }

    @Test
    fun `the failure detail is never silently dropped`() = runBlocking {
        val orderId = UUID.randomUUID()

        val record = records.upsertOpen(
            id = UUID.randomUUID(),
            orderId = orderId,
            kind = ReconciliationKind.INVARIANT_VIOLATION.name,
            orderState = OrderStatus.COMPENSATING.name,
            correlationId = "corr-1",
            failureClass = "reservation_order_conflict",
            failureDetail = null,
        )

        assertNull(record.failureDetail)
        assertEquals(OrderStatus.COMPENSATING.name, record.orderState)
    }

    private suspend fun open(orderId: UUID, kind: ReconciliationKind, failureClass: String) =
        records.upsertOpen(
            id = UUID.randomUUID(),
            orderId = orderId,
            kind = kind.name,
            orderState = OrderStatus.COMPENSATING.name,
            correlationId = "corr-1",
            failureClass = failureClass,
            failureDetail = "detail",
        )

    private suspend fun openFor(orderId: UUID) =
        records.findAllByOrderIdAndStatus(orderId, ReconciliationStatus.OPEN.name).toList()
}
