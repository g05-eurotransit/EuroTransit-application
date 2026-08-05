package it.polito.cpo.integration

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.client.AuthorizationOutcome
import it.polito.cpo.client.ConfirmReservationOutcome
import it.polito.cpo.client.HoldOutcome
import it.polito.cpo.client.InventoryClient
import it.polito.cpo.client.InventoryRefusalCode
import it.polito.cpo.client.PaymentClient
import it.polito.cpo.client.ReversalOutcome
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.controller.dtos.CheckoutRequest
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationStatus
import it.polito.cpo.repository.OrderRepository
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReconciliationRecordRepository
import it.polito.cpo.service.CheckoutOrchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import org.springframework.r2dbc.core.DatabaseClient
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test of the durable checkout pipeline inside orders, with real
 * infrastructure: a Testcontainers Postgres (R2DBC at runtime + Flyway migrations at startup) and an
 * embedded Kafka broker. Only the external money-path collaborators (inventory, payments) are
 * replaced by controllable in-process doubles.
 *
 * With the transactional outbox, `checkout()` no longer publishes to Kafka directly: it writes an
 * `order-placed` row into `outbox_events` in the same transaction as the order/idempotency records
 * (Debezium relays it in production). The test stands in for Debezium via [relayOrderPlaced], reading
 * the committed outbox row and publishing it to Kafka — then the real `OrderPlacedEventListener`
 * consumes it and drives the pipeline. This proves the atomic outbox write, the produce->consume
 * serialization roundtrip, the Flyway schema, and idempotent recovery under redelivery.
 *
 * The doubles are deterministic per request/order (a `DECLINE` payment token triggers a decline; all
 * bookkeeping is keyed by order id) so tests stay isolated despite the shared singleton beans and the
 * asynchronous consumer thread.
 */
@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
@EmbeddedKafka(partitions = 1, topics = ["order-placed", "order-confirmed", "notification-requested"])
@Testcontainers
@Import(CheckoutPipelineIntegrationTest.TestDoubles::class)
class CheckoutPipelineIntegrationTest {

    companion object {
        const val DECLINE_TOKEN = "DECLINE"

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

    @Autowired private lateinit var orchestrator: CheckoutOrchestrator
    @Autowired private lateinit var orderRepository: OrderRepository
    @Autowired private lateinit var outboxRepository: OutboxEventRepository
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var inventory: ControllableInventoryClient
    @Autowired private lateinit var payment: ControllablePaymentClient
    @Autowired private lateinit var reconciliationRepository: ReconciliationRecordRepository
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Test
    fun `happy path drives the order through the outbox and Kafka to CONFIRMED`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-happy-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        assertEquals(OrderStatus.ACCEPTED, response.status)

        relayOrderPlaced(response.orderId)

        val order = awaitTerminal(response.orderId)
        assertEquals(OrderStatus.CONFIRMED, order.status)
        assertTrue(payment.authorizedOrderIds.contains(response.orderId))
        assertFalse(inventory.releasedReservationIds.contains("res-${response.orderId}")) // no compensation
    }

    @Test
    fun `checkout writes exactly one order-placed outbox row atomically with the order`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-outbox-${UUID.randomUUID()}", "user-1", "user-1@example.com")

        val rows = outboxRepository.findAll().toList()
            .filter { it.aggregateId == response.orderId.toString() && it.type == "order-placed" }
        assertEquals(1, rows.size)
        // The order row is present too — the two were committed together.
        assertTrue(orderRepository.findById(response.orderId) != null)
    }

    @Test
    fun `a payment decline fails the order and compensates the seat hold`() = runBlocking {
        val response = orchestrator.checkout(request(token = DECLINE_TOKEN), "it-decline-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        relayOrderPlaced(response.orderId)
        val order = awaitTerminal(response.orderId)

        assertEquals(OrderStatus.FAILED, order.status)
        assertTrue(inventory.releasedReservationIds.contains("res-${response.orderId}")) // held seats released
        assertFalse(payment.refundedOrderIds.contains(response.orderId)) // nothing captured -> no refund
    }

    @Test
    fun `a redelivered order-placed is idempotent and does not reprocess a terminal order`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-redeliver-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        relayOrderPlaced(response.orderId)
        val order = awaitTerminal(response.orderId)
        assertEquals(OrderStatus.CONFIRMED, order.status)
        assertEquals(1, payment.authorizedOrderIds.count { it == response.orderId })

        // Simulate Kafka at-least-once redelivery of the same order-placed event, and wait until the
        // consumer has demonstrably handled it before asserting that nothing changed.
        val before = consumedCount()
        relayOrderPlaced(response.orderId)
        awaitEventsConsumed(before + 1)

        val reloaded = orderRepository.findById(response.orderId)!!
        assertEquals(OrderStatus.CONFIRMED, reloaded.status)
        assertEquals(1, payment.authorizedOrderIds.count { it == response.orderId }) // no re-authorization
    }

    @Test
    fun `cancelling before the pipeline runs leaves the order CANCELLED and the pipeline skips it`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-cancel-${UUID.randomUUID()}", "user-1", "user-1@example.com")

        // Cancel while still ACCEPTED (before relaying order-placed).
        val cancelled = orchestrator.cancel(response.orderId)
        assertEquals(OrderStatus.CANCELLED, cancelled.status)

        // The durable trigger still fires; the pipeline must skip a terminal (cancelled) order.
        val before = consumedCount()
        relayOrderPlaced(response.orderId)
        awaitEventsConsumed(before + 1)

        val reloaded = orderRepository.findById(response.orderId)!!
        assertEquals(OrderStatus.CANCELLED, reloaded.status)
        assertFalse(payment.authorizedOrderIds.contains(response.orderId)) // never charged
    }

    @Test
    fun `a duplicate idempotency key replays the same order without creating a new one`() = runBlocking {
        val key = "it-idem-${UUID.randomUUID()}"
        val first = orchestrator.checkout(request(), key, "user-1", "user-1@example.com")
        val second = orchestrator.checkout(request(), key, "user-1", "user-1@example.com")

        assertEquals(first.orderId, second.orderId)
    }

    @Test
    fun `a confirmation response lost after Inventory committed converges on redelivery`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-lost-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val reservationId = "res-${response.orderId}"
        // Drop the first response, after the sale is already committed on the Inventory side.
        inventory.loseConfirmResponses[reservationId] = AtomicInteger(1)

        relayOrderPlaced(response.orderId)

        val order = awaitTerminal(response.orderId)
        assertEquals(OrderStatus.CONFIRMED, order.status)
        // The replay is what completes it, and it must not have cost a second authorization.
        assertTrue(inventory.confirmCalls[reservationId]!!.get() >= 2)
        assertEquals(1, payment.authorizedOrderIds.count { it == response.orderId })
        // Invariant 8: an unknown outcome never became a compensation.
        assertFalse(payment.refundedOrderIds.contains(response.orderId))
        assertFalse(inventory.releasedReservationIds.contains(reservationId))
    }

    @Test
    fun `a crash between the Inventory sale and the Orders commit converges without duplicate events`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-crash-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val reservationId = "res-${response.orderId}"

        armCommitCrash(response.orderId)
        try {
            relayOrderPlaced(response.orderId)
            val order = awaitTerminal(response.orderId)
            assertEquals(OrderStatus.CONFIRMED, order.status)
        } finally {
            disarmCommitCrash()
        }

        // The confirmation was replayed, but the completed stages were not restarted.
        assertTrue(inventory.confirmCalls[reservationId]!!.get() >= 2)
        assertEquals(1, payment.authorizedOrderIds.count { it == response.orderId })
        assertFalse(payment.refundedOrderIds.contains(response.orderId))
        // Only the integration variant can prove this: the rolled-back attempt wrote no events.
        assertEquals(1, outboxRows(response.orderId, "order-confirmed").size)
        assertEquals(1, outboxRows(response.orderId, "notification-requested").size)
    }

    @Test
    fun `an expired reservation reverses the payment and fails the order`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-expired-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        inventory.refuseConfirmWith[response.orderId] = InventoryRefusalCode.RESERVATION_EXPIRED

        relayOrderPlaced(response.orderId)

        val order = awaitTerminal(response.orderId)
        assertEquals(OrderStatus.FAILED, order.status)
        assertTrue(payment.refundedOrderIds.contains(response.orderId))
        assertTrue(inventory.releasedReservationIds.contains("res-${response.orderId}"))
        // An ordinary race against the deadline opens no incident.
        assertTrue(openRecords(response.orderId).isEmpty())
    }

    @Test
    fun `an ownership conflict opens an invariant violation that stays open after the order fails`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-conflict-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        inventory.refuseConfirmWith[response.orderId] = InventoryRefusalCode.RESERVATION_ORDER_CONFLICT

        relayOrderPlaced(response.orderId)

        val order = awaitTerminal(response.orderId)
        assertEquals(OrderStatus.FAILED, order.status)
        assertTrue(payment.refundedOrderIds.contains(response.orderId))
        // The hold belongs to another order: releasing it would corrupt that checkout.
        assertFalse(inventory.releasedReservationIds.contains("res-${response.orderId}"))

        val open = openRecords(response.orderId)
        assertEquals(1, open.size)
        assertEquals(ReconciliationKind.INVARIANT_VIOLATION.name, open.single().kind)
        // The terminal transaction must NOT auto-close this kind: it needs an operator.
        assertEquals(ReconciliationStatus.OPEN.name, open.single().status)
    }

    @Test
    fun `a definitive reversal failure keeps the order non-terminal with an open incident`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-reversal-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        inventory.refuseConfirmWith[response.orderId] = InventoryRefusalCode.RESERVATION_EXPIRED
        payment.failReversalFor.add(response.orderId)

        relayOrderPlaced(response.orderId)

        val parked = awaitStatus(response.orderId, OrderStatus.COMPENSATING)
        // Unresolved money must not read as a finished order.
        assertEquals(OrderStatus.COMPENSATING, parked.status)
        val open = openRecords(response.orderId)
        assertEquals(1, open.size)
        assertEquals(ReconciliationKind.PAYMENT_REVERSAL_FAILURE.name, open.single().kind)
    }

    private fun request(token: String = "tok-123") =
        CheckoutRequest(routeId = "R1", seats = listOf("1A", "1B"), totalAmount = BigDecimal("100.00"), paymentMethodToken = token)

    // Stands in for Debezium: reads the committed order-placed outbox row(s) for this order and
    // publishes the JSON payload to Kafka, keyed by aggregate id (the order id).
    private suspend fun relayOrderPlaced(orderId: UUID) {
        outboxRepository.findAll().toList()
            .filter { it.aggregateId == orderId.toString() && it.type == "order-placed" }
            .forEach { kafkaTemplate.send("order-placed", it.aggregateId, it.payload.asString()) }
    }

    private suspend fun awaitTerminal(id: UUID): Order = withTimeout(30_000) {
        var order = orderRepository.findById(id)
        while (order == null || order.status !in TERMINAL) {
            delay(200)
            order = orderRepository.findById(id)
        }
        order
    }

    private suspend fun awaitStatus(id: UUID, status: OrderStatus): Order = withTimeout(30_000) {
        var order = orderRepository.findById(id)
        while (order == null || order.status != status) {
            delay(200)
            order = orderRepository.findById(id)
        }
        order
    }

    /**
     * Waits for the consumer to have processed at least [target] events before asserting that nothing
     * happened. Waiting on a positive signal rather than sleeping is what makes a "must NOT happen"
     * assertion falsifiable: a fixed delay passes just as happily when the consumer never ran at all.
     */
    private suspend fun awaitEventsConsumed(target: Double) = withTimeout(30_000) {
        while (consumedCount() < target) delay(100)
    }

    private fun consumedCount(): Double =
        meterRegistry.find("orders.events.consumed").counters().sumOf { it.count() }

    private suspend fun outboxRows(orderId: UUID, type: String) =
        outboxRepository.findAll().toList().filter { it.aggregateId == orderId.toString() && it.type == type }

    private suspend fun openRecords(orderId: UUID) =
        reconciliationRepository.findAllByOrderIdAndStatus(orderId, ReconciliationStatus.OPEN.name).toList()

    /**
     * Arms a one-shot failure of the terminal Orders transaction for [orderId], standing in for a crash
     * after Inventory has committed CONFIRMED/SOLD but before Orders commits.
     *
     * A sequence is used deliberately: `nextval()` is explicitly non-transactional in PostgreSQL, so the
     * counter advances even though the RAISE aborts the transaction. A flag row would be rolled back
     * with the aborted transaction, the trigger would fire on every retry, and the order would never
     * converge.
     */
    private suspend fun armCommitCrash(orderId: UUID) {
        exec("CREATE SEQUENCE IF NOT EXISTS test_commit_crash_seq")
        exec("SELECT setval('test_commit_crash_seq', 1, false)")
        exec(
            """
            CREATE OR REPLACE FUNCTION test_crash_before_confirm() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                IF NEW.status = 'CONFIRMED'
                   AND NEW.id = '$orderId'::uuid
                   AND nextval('test_commit_crash_seq') = 1 THEN
                    RAISE EXCEPTION 'simulated orders crash after inventory confirmation';
                END IF;
                RETURN NEW;
            END; ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        exec("DROP TRIGGER IF EXISTS test_crash_confirm ON orders")
        exec(
            "CREATE TRIGGER test_crash_confirm BEFORE UPDATE ON orders " +
                "FOR EACH ROW EXECUTE FUNCTION test_crash_before_confirm()",
        )
    }

    private suspend fun disarmCommitCrash() = exec("DROP TRIGGER IF EXISTS test_crash_confirm ON orders")

    private suspend fun exec(sql: String) {
        databaseClient.sql(sql).then().awaitSingleOrNull()
    }

    private val TERMINAL = setOf(OrderStatus.CONFIRMED, OrderStatus.FAILED, OrderStatus.CANCELLED)

    /**
     * Controllable in-process doubles for the external money-path collaborators. Behaviour is derived
     * from the request (a [DECLINE_TOKEN] payment token declines), and all bookkeeping is keyed by
     * order id, so concurrent/async processing across tests never cross-contaminates.
     */
    class ControllableInventoryClient : InventoryClient {
        val reservedOrderIds = CopyOnWriteArrayList<UUID>()
        val releasedReservationIds = ConcurrentHashMap.newKeySet<String>()

        /** Stands in for Inventory's committed state, so a replay observes an already-sold hold. */
        val confirmedReservationIds = ConcurrentHashMap.newKeySet<String>()
        val confirmCalls = ConcurrentHashMap<String, AtomicInteger>()

        /** reservationId -> how many responses to drop before answering normally. */
        val loseConfirmResponses = ConcurrentHashMap<String, AtomicInteger>()

        /** orderId -> a definitive refusal to answer the confirmation with. */
        val refuseConfirmWith = ConcurrentHashMap<UUID, InventoryRefusalCode>()

        override suspend fun reserveSeats(
            request: ReservationRequest,
            idempotencyKey: String,
            userId: String,
        ): HoldOutcome {
            reservedOrderIds.add(request.orderId)
            return HoldOutcome.Held("res-${request.orderId}", LocalDateTime.now().plusMinutes(10))
        }

        override suspend fun confirmReservation(reservationId: String, orderId: UUID): ConfirmReservationOutcome {
            confirmCalls.computeIfAbsent(reservationId) { AtomicInteger() }.incrementAndGet()

            refuseConfirmWith[orderId]?.let { return ConfirmReservationOutcome.Refused(it, "forced by test") }

            // Inventory commits CONFIRMED + SOLD before it writes the response, exactly as the real
            // transaction does, so a dropped response must still leave the sale committed.
            confirmedReservationIds.add(reservationId)
            if ((loseConfirmResponses[reservationId]?.getAndDecrement() ?: 0) > 0) {
                throw IOException("simulated connection reset after Inventory committed")
            }
            return ConfirmReservationOutcome.Confirmed(reservationId)
        }

        override suspend fun releaseSeats(reservationId: String, idempotencyKey: String) {
            releasedReservationIds.add(reservationId)
        }
    }

    class ControllablePaymentClient : PaymentClient {
        val authorizedOrderIds = CopyOnWriteArrayList<UUID>()
        val refundedOrderIds = CopyOnWriteArrayList<UUID>()

        /** orderId -> answer the reversal with a definitive failure instead of success. */
        val failReversalFor = ConcurrentHashMap.newKeySet<UUID>()

        override suspend fun authorizePayment(
            request: PaymentRequest,
            idempotencyKey: String,
            correlationId: String,
        ): AuthorizationOutcome {
            if (request.paymentMethodToken == DECLINE_TOKEN) {
                return AuthorizationOutcome.Declined("DECLINED")
            }
            authorizedOrderIds.add(request.orderId)
            return AuthorizationOutcome.Authorized("test-ref")
        }

        override suspend fun refund(orderId: UUID, idempotencyKey: String): ReversalOutcome {
            refundedOrderIds.add(orderId)
            return if (orderId in failReversalFor) {
                ReversalOutcome.Failed("FAILED", "forced by test")
            } else {
                ReversalOutcome.Reversed
            }
        }
    }

    @TestConfiguration
    class TestDoubles {
        @Bean @Primary fun inventoryDouble() = ControllableInventoryClient()

        @Bean @Primary fun paymentDouble() = ControllablePaymentClient()

        // No real Keycloak in the test: back off the network-fetching decoder. It is never invoked
        // because these tests drive the orchestrator directly rather than over HTTP.
        @Bean @Primary fun testJwtDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { Mono.empty() }
    }
}
