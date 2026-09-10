package it.polito.cpo.repository

import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.service.CustomerFailureCode
import kotlinx.coroutines.runBlocking
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
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves the money-path transition SQL against real PostgreSQL, one (from-state x method) pair at a
 * time. This is deliberately a repository-level test rather than a pipeline one: the compare-and-set
 * predicates are what make Kafka redelivery and concurrent cancellation safe, and a bug in them is
 * far cheaper to find here than through the asynchronous orchestrator.
 *
 * Two properties matter most and are asserted for every state:
 *
 *  * phase markers accept their own target state, so a redelivery that repeats a completed stage
 *    still observes 1 row affected and proceeds;
 *  * terminal commits do NOT, so a concurrent double delivery cannot write the paired outbox rows
 *    twice.
 *
 * The absence of a `CONFIRMING_INVENTORY -> FAILED` path is asserted explicitly: it is what enforces
 * "Orders reaches FAILED after authorization only when the payment reversal has completed
 * successfully" (docs/design/reservation-confirmation.md:150-152).
 *
 * The Kafka listener is switched off: this test drives the repository directly and must not race
 * with a consumer, and it needs no broker.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.kafka.listener.auto-startup=false"],
)
@Testcontainers
@Import(OrderTransitionsIntegrationTest.NoRemoteJwks::class)
class OrderTransitionsIntegrationTest {

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

        private val ALL_STATES = OrderStatus.entries

        /**
         * Any instant will do; what matters is that it survives the transition unchanged.
         *
         * Truncated to microseconds because that is the resolution `timestamptz` stores: a JVM
         * clock with nanosecond resolution - which the Linux CI runners have and macOS does not -
         * would otherwise lose its last three digits on the round-trip and fail the comparison
         * below on the build machine only.
         */
        private val HOLD_DEADLINE: OffsetDateTime =
            OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS).plusMinutes(10)
    }

    @TestConfiguration
    class NoRemoteJwks {
        /** Keeps context startup off the network; no request is authenticated here. */
        @Bean
        @Primary
        fun testJwtDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { Mono.empty() }
    }

    @Autowired private lateinit var orders: OrderRepository

    // --- phase markers: accept the expected predecessor AND their own target state ---------------

    @Test
    fun `markReserving accepts ACCEPTED and repeats itself, and nothing else`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.ACCEPTED, OrderStatus.RESERVING),
            resultingIn = OrderStatus.RESERVING,
        ) { id -> orders.markReserving(id) }
    }

    @Test
    fun `markConfirmingInventory accepts PAYMENT_PENDING and repeats itself, and nothing else`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.PAYMENT_PENDING, OrderStatus.CONFIRMING_INVENTORY),
            resultingIn = OrderStatus.CONFIRMING_INVENTORY,
        ) { id -> orders.markConfirmingInventory(id) }
    }

    @Test
    fun `markCompensating accepts CONFIRMING_INVENTORY and repeats itself, and nothing else`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.CONFIRMING_INVENTORY, OrderStatus.COMPENSATING),
            resultingIn = OrderStatus.COMPENSATING,
        ) { id -> orders.markCompensating(id, "RESERVATION_EXPIRED") }
    }

    // --- terminal commits: no self-transition, so their outbox rows are written exactly once ------

    @Test
    fun `markConfirmed accepts only CONFIRMING_INVENTORY and refuses to repeat itself`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.CONFIRMING_INVENTORY),
            resultingIn = OrderStatus.CONFIRMED,
        ) { id -> orders.markConfirmed(id) }
    }

    @Test
    fun `markFailedUnpaid accepts only the pre-authorization phases`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.RESERVING, OrderStatus.PAYMENT_PENDING),
            resultingIn = OrderStatus.FAILED,
        ) { id -> orders.markFailedUnpaid(id, CustomerFailureCode.CHECKOUT_FAILED.name) }
    }

    @Test
    fun `markFailedAfterReversal accepts only COMPENSATING`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.COMPENSATING),
            resultingIn = OrderStatus.FAILED,
        ) { id -> orders.markFailedAfterReversal(id, CustomerFailureCode.CHECKOUT_FAILED.name) }
    }

    @Test
    fun `cancelIfUnpaid accepts only ACCEPTED and RESERVING`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.ACCEPTED, OrderStatus.RESERVING),
            resultingIn = OrderStatus.CANCELLED,
        ) { id -> orders.cancelIfUnpaid(id) }
    }

    // --- the invariant that has no SQL path -------------------------------------------------------

    @Test
    fun `there is no path from CONFIRMING_INVENTORY to FAILED`() = runBlocking {
        val viaUnpaid = seed(OrderStatus.CONFIRMING_INVENTORY)
        assertEquals(0L, orders.markFailedUnpaid(viaUnpaid, CustomerFailureCode.CHECKOUT_FAILED.name))
        assertEquals(OrderStatus.CONFIRMING_INVENTORY, statusOf(viaUnpaid))

        val viaReversal = seed(OrderStatus.CONFIRMING_INVENTORY)
        assertEquals(0L, orders.markFailedAfterReversal(viaReversal, CustomerFailureCode.CHECKOUT_FAILED.name))
        assertEquals(OrderStatus.CONFIRMING_INVENTORY, statusOf(viaReversal))
    }

    // --- markPaymentPending: pinned to one reservation id ----------------------------------------

    @Test
    fun `markPaymentPending stores the reservation id from RESERVING`() = runBlocking {
        val id = seed(OrderStatus.RESERVING)
        assertEquals(1L, orders.markPaymentPending(id, "res-1", HOLD_DEADLINE))

        val order = orders.findById(id)!!
        assertEquals(OrderStatus.PAYMENT_PENDING, order.status)
        assertEquals("res-1", order.reservationId)
        // The deadline is written by the same statement, so it can never be missing while the id is
        // present. Compared as instants: a timestamptz round-trip is free to come back in a
        // different offset, and only the instant is the contract.
        assertEquals(HOLD_DEADLINE.toInstant(), order.reservationExpiresAt?.toInstant())
    }

    @Test
    fun `markPaymentPending repeats itself only for the same reservation id`() = runBlocking {
        val id = seed(OrderStatus.RESERVING)
        orders.markPaymentPending(id, "res-1", HOLD_DEADLINE)

        // An idempotent hold replay resolves the original reservation, so the same id must succeed.
        assertEquals(1L, orders.markPaymentPending(id, "res-1", HOLD_DEADLINE))
        assertEquals("res-1", orders.findById(id)!!.reservationId)

        // A different id means the hold contract was broken: refuse rather than rebind the order.
        assertEquals(0L, orders.markPaymentPending(id, "res-2", HOLD_DEADLINE))
        assertEquals("res-1", orders.findById(id)!!.reservationId)
    }

    @Test
    fun `markPaymentPending is refused from every other state`() = runBlocking {
        val refused = ALL_STATES - setOf(OrderStatus.RESERVING, OrderStatus.PAYMENT_PENDING)
        refused.forEach { from ->
            val id = seed(from)
            assertEquals(0L, orders.markPaymentPending(id, "res-1", HOLD_DEADLINE), "expected refusal from $from")
            assertEquals(from, statusOf(id))
            assertNull(orders.findById(id)!!.reservationId, "reservation id leaked from $from")
        }
    }

    // --- side effects on the row ------------------------------------------------------------------

    @Test
    fun `markConfirmed clears the payment token`() = runBlocking {
        val id = seed(OrderStatus.CONFIRMING_INVENTORY, paymentMethodToken = "tok-live")
        orders.markConfirmed(id)
        assertNull(orders.findById(id)!!.paymentMethodToken)
    }

    @Test
    fun `markCompensating persists the failure class for a post-crash resume`() = runBlocking {
        val id = seed(OrderStatus.CONFIRMING_INVENTORY)
        orders.markCompensating(id, "RESERVATION_ORDER_CONFLICT")
        assertEquals("RESERVATION_ORDER_CONFLICT", orders.findById(id)!!.compensationReason)
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Runs [transition] once from every [OrderStatus], asserting 1 row exactly for the states in
     * [accepting] (and that the row now reads [resultingIn]) and 0 rows plus an untouched row for
     * every other state.
     */
    private suspend fun assertRowsAffected(
        accepting: Set<OrderStatus>,
        resultingIn: OrderStatus,
        transition: suspend (UUID) -> Long,
    ) {
        ALL_STATES.forEach { from ->
            val id = seed(from)
            val rows = transition(id)
            if (from in accepting) {
                assertEquals(1L, rows, "expected $from to be accepted")
                assertEquals(resultingIn, statusOf(id), "expected $from to advance")
            } else {
                assertEquals(0L, rows, "expected $from to be refused")
                assertEquals(from, statusOf(id), "expected $from to be left untouched")
            }
        }
    }

    private suspend fun statusOf(id: UUID): OrderStatus = orders.findById(id)!!.status

    private suspend fun seed(
        status: OrderStatus,
        paymentMethodToken: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        orders.save(
            Order(
                id = id,
                userId = "user-transitions",
                status = status,
                routeId = "route-1",
                seats = "A1",
                travelDate = LocalDate.now().plusDays(3),
                totalAmount = BigDecimal("10.00"),
                createdAt = LocalDateTime.now(),
                paymentMethodToken = paymentMethodToken,
                recipientEmail = "user-transitions@example.com",
            ).apply { setAsNew(true) },
        )
        return id
    }
}
