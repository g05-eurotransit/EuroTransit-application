package it.polito.cpo.repository

import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.service.CustomerFailureCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DuplicateKeyException
import org.springframework.r2dbc.core.DatabaseClient
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
    @Autowired private lateinit var db: DatabaseClient

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
    fun `cancelIfUnpaid accepts only ACCEPTED, RESERVING and HELD_AWAITING_PAYMENT`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.ACCEPTED, OrderStatus.RESERVING, OrderStatus.HELD_AWAITING_PAYMENT),
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

    @Test
    fun `the offer the order was bought against survives a round-trip`() = runBlocking {
        // Proves V13 and the entity mapping agree. The refund (ADR-015) resolves its policy from the
        // offer, so an order that silently forgot it would be unrefundable with no sign of why.
        val id = seed(OrderStatus.CONFIRMED, offerId = "of-001")
        assertEquals("of-001", orders.findById(id)!!.offerId)
    }

    @Test
    fun `an order can be persisted without an offer`() = runBlocking {
        // The column is nullable until the SPA sends the field (issue #84); a NOT NULL constraint
        // here would break every checkout in the meantime.
        val id = seed(OrderStatus.CONFIRMED)
        assertNull(orders.findById(id)!!.offerId)
    }

    @Test
    fun `the frozen refund terms survive a round-trip`() = runBlocking {
        // Proves V14 and the entity mapping agree. These are the inputs the refund decides on, and
        // an order that silently forgot them would be unrefundable with no sign of why.
        val id = seed(
            OrderStatus.CONFIRMED,
            offerId = "of-002",
            refundable = true,
            refundCutoffMinutesBeforeDeparture = 5,
            offerTermsVersion = 3,
        )

        val stored = orders.findById(id)!!
        assertEquals(true, stored.refundable)
        assertEquals(5, stored.refundCutoffMinutesBeforeDeparture)
        assertEquals(3, stored.offerTermsVersion)
    }

    @Test
    fun `the departure instant comes back as the same moment, not the same local time`() = runBlocking {
        // The reason the column is TIMESTAMPTZ. A departure written without its offset would come
        // back reinterpreted in the session's zone, and the refund cutoff — a duration before this
        // instant — would move by that offset, differently on each side of a DST boundary.
        val departure = OffsetDateTime.parse("2026-10-01T07:00:00+02:00")
        val id = seed(OrderStatus.CONFIRMED, offerId = "of-002", departureAt = departure)

        assertEquals(departure.toInstant(), orders.findById(id)!!.departureAt!!.toInstant())
    }

    @Test
    fun `the currency is persisted beside the amount`() = runBlocking {
        // The pair the authorization is built from. They used to come from two different places —
        // the amount from the request, the currency from a literal — and only one was ever checked.
        val id = seed(OrderStatus.CONFIRMED, offerId = "of-004", currency = "CHF")

        val stored = orders.findById(id)!!
        assertEquals("CHF", stored.currency)
        assertEquals(0, BigDecimal("10.00").compareTo(stored.totalAmount))
    }

    @Test
    fun `an order without an offer has no refund terms and still defaults to euros`() = runBlocking {
        // The currency column is NOT NULL, so an offer-less checkout has to write something; "EUR"
        // is the old hardcode, now recorded per order instead of asserted at payment time.
        val id = seed(OrderStatus.CONFIRMED)

        val stored = orders.findById(id)!!
        assertEquals("EUR", stored.currency)
        assertNull(stored.refundable)
        assertNull(stored.refundCutoffMinutesBeforeDeparture)
        assertNull(stored.offerTermsVersion)
        assertNull(stored.departureAt)
    }

    // --- customer-hold flow (ADR-016) -------------------------------------------------------------

    @Test
    fun `markHeldAwaitingPayment parks a customer-hold order from RESERVING and repeats itself`() = runBlocking {
        val id = seed(OrderStatus.RESERVING, paymentDeferred = true)
        assertEquals(1L, orders.markHeldAwaitingPayment(id, "res-1", HOLD_DEADLINE))
        val order = orders.findById(id)!!
        assertEquals(OrderStatus.HELD_AWAITING_PAYMENT, order.status)
        assertEquals("res-1", order.reservationId)
        assertEquals(HOLD_DEADLINE.toInstant(), order.reservationExpiresAt?.toInstant())

        // A redelivered hold resolves the same reservation and rewrites the same values.
        assertEquals(1L, orders.markHeldAwaitingPayment(id, "res-1", HOLD_DEADLINE))
        // A different reservation id is a violated hold contract, never a silent rebinding.
        assertEquals(0L, orders.markHeldAwaitingPayment(id, "res-other", HOLD_DEADLINE))
    }

    @Test
    fun `markHeldAwaitingPayment never parks a direct-flow order, and accepts no other state`() = runBlocking {
        val direct = seed(OrderStatus.RESERVING)
        assertEquals(0L, orders.markHeldAwaitingPayment(direct, "res-1", HOLD_DEADLINE))
        assertEquals(OrderStatus.RESERVING, statusOf(direct))

        (ALL_STATES - OrderStatus.RESERVING - OrderStatus.HELD_AWAITING_PAYMENT).forEach { from ->
            val id = seed(from, paymentDeferred = true)
            assertEquals(0L, orders.markHeldAwaitingPayment(id, "res-1", HOLD_DEADLINE), "expected $from to be refused")
            assertEquals(from, statusOf(id))
        }
    }

    @Test
    fun `submitPayment accepts only a held order inside T1, stamps the database time, and never repeats`() = runBlocking {
        (ALL_STATES - OrderStatus.HELD_AWAITING_PAYMENT).forEach { from ->
            val id = seed(from, paymentDeferred = true, reservationExpiresAt = OffsetDateTime.now().plusMinutes(5))
            assertEquals(0L, orders.submitPayment(id, "tok_visa"), "expected $from to be refused")
            assertEquals(from, statusOf(id))
        }

        val id = heldOrder("5 minutes")
        assertEquals(1L, orders.submitPayment(id, "tok_visa"))
        val order = orders.findById(id)!!
        assertEquals(OrderStatus.PAYMENT_PENDING, order.status)
        assertEquals("tok_visa", order.paymentMethodToken)
        assertNotNull(order.paymentSubmittedAt)

        // No self-transition: a second submission must learn that the payment already started.
        assertEquals(0L, orders.submitPayment(id, "tok_other"))
        assertEquals("tok_visa", orders.findById(id)!!.paymentMethodToken)
    }

    @Test
    fun `T1 boundary - one second either side of the deadline by the database clock`() = runBlocking {
        val inside = heldOrder("1 second")
        assertEquals(1L, orders.submitPayment(inside, "tok_visa"))
        assertEquals(0L, orders.cancelExpiredHold(heldOrder("1 second")))

        val past = heldOrder("-1 second")
        assertEquals(0L, orders.submitPayment(past, "tok_visa"))
        assertEquals(OrderStatus.HELD_AWAITING_PAYMENT, statusOf(past))
        assertEquals(1L, orders.cancelExpiredHold(past))
        assertEquals(OrderStatus.CANCELLED, statusOf(past))
    }

    @Test
    fun `submitPayment never accepts a direct-flow order`() = runBlocking {
        val id = seed(OrderStatus.HELD_AWAITING_PAYMENT, reservationExpiresAt = OffsetDateTime.now().plusMinutes(5))
        assertEquals(0L, orders.submitPayment(id, "tok_visa"))
    }

    @Test
    fun `cancelExpiredHold accepts only a held order past its deadline`() = runBlocking {
        (ALL_STATES - OrderStatus.HELD_AWAITING_PAYMENT).forEach { from ->
            val id = seed(from, paymentDeferred = true, reservationExpiresAt = OffsetDateTime.now().minusMinutes(5))
            assertEquals(0L, orders.cancelExpiredHold(id), "expected $from to be refused")
            assertEquals(from, statusOf(id))
        }
    }

    @Test
    fun `payment, cancellation and the expiry job race from HELD_AWAITING_PAYMENT with exactly one winner`() = runBlocking {
        repeat(20) {
            val id = heldOrder("5 minutes")
            val rows = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { orders.submitPayment(id, "tok_visa") },
                    async(Dispatchers.IO) { orders.cancelIfUnpaid(id) },
                    async(Dispatchers.IO) { orders.cancelExpiredHold(id) },
                ).awaitAll()
            }
            assertEquals(1L, rows.sum(), "exactly one writer must win, got $rows")
            assertEquals(0L, rows[2], "the expiry job must not win inside T1")
            val expected = if (rows[0] == 1L) OrderStatus.PAYMENT_PENDING else OrderStatus.CANCELLED
            assertEquals(expected, statusOf(id))
        }
    }

    @Test
    fun `after T1, payment and the expiry job race and only the job can win`() = runBlocking {
        repeat(20) {
            val id = heldOrder("-1 millisecond")
            val rows = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { orders.submitPayment(id, "tok_visa") },
                    async(Dispatchers.IO) { orders.cancelExpiredHold(id) },
                ).awaitAll()
            }
            assertEquals(listOf(0L, 1L), rows)
            assertEquals(OrderStatus.CANCELLED, statusOf(id))
        }
    }

    @Test
    fun `markPaymentWindowOpened records T2 only for the stored reservation of a paid customer-hold order`() = runBlocking {
        val t2 = HOLD_DEADLINE.plusMinutes(10)
        (ALL_STATES - OrderStatus.PAYMENT_PENDING).forEach { from ->
            val id = seed(from, paymentDeferred = true, reservationId = "res-1")
            assertEquals(0L, orders.markPaymentWindowOpened(id, "res-1", t2), "expected $from to be refused")
        }
        val direct = seed(OrderStatus.PAYMENT_PENDING, reservationId = "res-1")
        assertEquals(0L, orders.markPaymentWindowOpened(direct, "res-1", t2))

        val id = seed(OrderStatus.PAYMENT_PENDING, paymentDeferred = true, reservationId = "res-1", reservationExpiresAt = HOLD_DEADLINE)
        assertEquals(0L, orders.markPaymentWindowOpened(id, "res-other", t2))
        assertEquals(1L, orders.markPaymentWindowOpened(id, "res-1", t2))
        assertEquals(1L, orders.markPaymentWindowOpened(id, "res-1", t2), "a redelivery rewrites the same values")
        val order = orders.findById(id)!!
        assertTrue(order.paymentWindowOpened)
        assertEquals(t2.toInstant(), order.reservationExpiresAt?.toInstant())
    }

    @Test
    fun `one active hold per customer - the second concurrent insert fails, direct-flow orders do not count`() = runBlocking<Unit> {
        val user = "user-one-hold-${UUID.randomUUID()}"
        val results = coroutineScope {
            List(2) {
                async(Dispatchers.IO) {
                    runCatching { seed(OrderStatus.ACCEPTED, paymentDeferred = true, userId = user) }
                }
            }.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is DuplicateKeyException)

        // Direct-flow orders of the same customer are unaffected, however many there are.
        repeat(3) { seed(OrderStatus.ACCEPTED, userId = user) }

        // Once the held order ends, the customer may hold again.
        val active = orders.findActiveHold(user)!!
        assertEquals(1L, orders.cancelIfUnpaid(active.id))
        seed(OrderStatus.ACCEPTED, paymentDeferred = true, userId = user)
    }

    @Test
    fun `the index counts every waiting state, and a paid order frees the customer`() = runBlocking<Unit> {
        listOf(OrderStatus.ACCEPTED, OrderStatus.RESERVING, OrderStatus.HELD_AWAITING_PAYMENT).forEach { waiting ->
            val user = "user-waiting-${UUID.randomUUID()}"
            seed(waiting, paymentDeferred = true, userId = user)
            assertThrows<DuplicateKeyException> { seed(OrderStatus.ACCEPTED, paymentDeferred = true, userId = user) }
        }
        val user = "user-paid-${UUID.randomUUID()}"
        val paid = heldOrder("5 minutes", userId = user)
        assertEquals(1L, orders.submitPayment(paid, "tok_visa"))
        seed(OrderStatus.ACCEPTED, paymentDeferred = true, userId = user)
    }

    @Test
    fun `pipeline age of a customer-hold order starts at the payment, not at creation`() = runBlocking {
        val id = UUID.randomUUID()
        orders.save(
            Order(
                id = id,
                userId = "user-age-${UUID.randomUUID()}",
                status = OrderStatus.HELD_AWAITING_PAYMENT,
                routeId = "route-1",
                seats = "A1",
                travelDate = LocalDate.now().plusDays(3),
                totalAmount = BigDecimal("10.00"),
                currency = "EUR",
                // Created nine minutes ago: the customer read the page for most of T1.
                createdAt = LocalDateTime.now().minusMinutes(9),
                paymentDeferred = true,
                reservationId = "res-age",
            ).apply { setAsNew(true) },
        )
        setDeadline(id, "1 minute")
        assertEquals(1L, orders.submitPayment(id, "tok_visa"))

        val row = orders.countNonTerminalByState().toList().single { it.status == OrderStatus.PAYMENT_PENDING.name }
        assertTrue(row.oldestAgeSeconds < 60.0, "age must start at the payment, was ${row.oldestAgeSeconds}s")
    }

    @Test
    fun `findExpiredHolds and countOverdueHolds see only held orders past T1`() = runBlocking {
        val overdue = heldOrder("-30 seconds")
        val running = heldOrder("5 minutes")

        val expired = orders.findExpiredHolds(1000).toList().map { it.id }
        assertTrue(overdue in expired)
        assertFalse(running in expired)
        val count = orders.countOverdueHolds()
        assertTrue(count.orderCount >= 1)
        assertTrue(count.oldestAgeSeconds >= 30.0)
    }

    // --- post-sale refund (ADR-015) -----------------------------------------------------------------

    @Test
    fun `markRefunding accepts only CONFIRMED, and only a refundable order inside its cutoff`() = runBlocking {
        val refused = ALL_STATES - OrderStatus.CONFIRMED
        refused.forEach { from ->
            // Every eligibility field is set to what would otherwise be accepted, so a refusal from a
            // non-CONFIRMED state proves the status predicate is what refused it, not a missing field.
            val id = seed(
                from,
                offerId = "of-002",
                refundable = true,
                refundCutoffMinutesBeforeDeparture = 60,
                departureAt = OffsetDateTime.now().plusDays(1),
            )
            assertEquals(0L, orders.markRefunding(id), "expected $from to be refused")
            assertEquals(from, statusOf(id), "expected $from to be left untouched")
        }

        val id = seed(
            OrderStatus.CONFIRMED,
            offerId = "of-002",
            refundable = true,
            refundCutoffMinutesBeforeDeparture = 60,
            departureAt = OffsetDateTime.now().plusDays(1),
        )
        assertEquals(1L, orders.markRefunding(id))
        val order = orders.findById(id)!!
        assertEquals(OrderStatus.REFUNDING, order.status)
        assertNotNull(order.refundRequestedAt)
    }

    @Test
    fun `markRefunding refuses an order with no offer`() = runBlocking {
        val id = seed(OrderStatus.CONFIRMED) // no offerId, no refund terms
        assertEquals(0L, orders.markRefunding(id))
        assertEquals(OrderStatus.CONFIRMED, statusOf(id))
    }

    @Test
    fun `markRefunding refuses an offer that is not refundable`() = runBlocking {
        val id = seed(OrderStatus.CONFIRMED, offerId = "of-001", refundable = false)
        assertEquals(0L, orders.markRefunding(id))
        assertEquals(OrderStatus.CONFIRMED, statusOf(id))
    }

    @Test
    fun `markRefunding refuses a request past the cutoff`() = runBlocking {
        // departure_at - cutoff is 30 minutes in the past: CURRENT_TIMESTAMP is no longer before it.
        val id = seed(
            OrderStatus.CONFIRMED,
            offerId = "of-002",
            refundable = true,
            refundCutoffMinutesBeforeDeparture = 60,
            departureAt = OffsetDateTime.now().minusMinutes(30),
        )
        assertEquals(0L, orders.markRefunding(id))
        assertEquals(OrderStatus.CONFIRMED, statusOf(id))
    }

    @Test
    fun `a null cutoff means refundable right up to departure itself`() = runBlocking {
        // No cutoff column set (null), so only departure_at itself is the boundary. Still comfortably
        // in the future, so the refund is accepted.
        val id = seed(
            OrderStatus.CONFIRMED,
            offerId = "of-005",
            refundable = true,
            refundCutoffMinutesBeforeDeparture = null,
            departureAt = OffsetDateTime.now().plusMinutes(5),
        )
        assertEquals(1L, orders.markRefunding(id))
        assertEquals(OrderStatus.REFUNDING, statusOf(id))
    }

    @Test
    fun `markRefunded accepts only REFUNDING and refuses to repeat itself`() = runBlocking {
        assertRowsAffected(
            accepting = setOf(OrderStatus.REFUNDING),
            resultingIn = OrderStatus.REFUNDED,
        ) { id -> orders.markRefunded(id, "prov-ref-1") }
    }

    @Test
    fun `markRefunded stamps refunded_at and the provider reference`() = runBlocking {
        val id = seed(
            OrderStatus.CONFIRMED,
            offerId = "of-002",
            refundable = true,
            refundCutoffMinutesBeforeDeparture = 60,
            departureAt = OffsetDateTime.now().plusDays(1),
        )
        orders.markRefunding(id)

        assertEquals(1L, orders.markRefunded(id, "prov-ref-42"))
        val order = orders.findById(id)!!
        assertEquals(OrderStatus.REFUNDED, order.status)
        assertNotNull(order.refundedAt)
        assertEquals("prov-ref-42", order.refundProviderReference)
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
        paymentDeferred: Boolean = false,
        // Customer-hold seeds get a user of their own: the one-active-hold index would otherwise make
        // the second deferred ACCEPTED/RESERVING/HELD seed of a test collide with the first.
        userId: String = if (paymentDeferred) "user-hold-${UUID.randomUUID()}" else "user-transitions",
        reservationId: String? = null,
        reservationExpiresAt: OffsetDateTime? = null,
        paymentMethodToken: String? = null,
        offerId: String? = null,
        currency: String = "EUR",
        refundable: Boolean? = null,
        refundCutoffMinutesBeforeDeparture: Int? = null,
        offerTermsVersion: Int? = null,
        departureAt: OffsetDateTime? = null,
    ): UUID {
        val id = UUID.randomUUID()
        orders.save(
            Order(
                id = id,
                userId = userId,
                status = status,
                routeId = "route-1",
                seats = "A1",
                travelDate = LocalDate.now().plusDays(3),
                totalAmount = BigDecimal("10.00"),
                currency = currency,
                createdAt = LocalDateTime.now(),
                paymentMethodToken = paymentMethodToken,
                recipientEmail = "user-transitions@example.com",
                offerId = offerId,
                refundable = refundable,
                refundCutoffMinutesBeforeDeparture = refundCutoffMinutesBeforeDeparture,
                offerTermsVersion = offerTermsVersion,
                departureAt = departureAt,
                paymentDeferred = paymentDeferred,
                reservationId = reservationId,
                reservationExpiresAt = reservationExpiresAt,
            ).apply { setAsNew(true) },
        )
        return id
    }

    /** Places the order's hold deadline relative to the DATABASE clock, the only one the SQL reads. */
    private suspend fun setDeadline(id: UUID, offset: String) {
        db.sql("UPDATE orders SET reservation_expires_at = CURRENT_TIMESTAMP + CAST(:offset AS INTERVAL) WHERE id = :id")
            .bind("offset", offset)
            .bind("id", id)
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun heldOrder(deadlineOffset: String, userId: String? = null): UUID {
        val id = if (userId == null) {
            seed(OrderStatus.HELD_AWAITING_PAYMENT, paymentDeferred = true, reservationId = "res-held")
        } else {
            seed(OrderStatus.HELD_AWAITING_PAYMENT, paymentDeferred = true, userId = userId, reservationId = "res-held")
        }
        setDeadline(id, deadlineOffset)
        return id
    }
}
