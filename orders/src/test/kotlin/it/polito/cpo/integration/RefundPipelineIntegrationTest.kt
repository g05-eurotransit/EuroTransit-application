package it.polito.cpo.integration

import io.micrometer.core.instrument.MeterRegistry
import it.polito.cpo.client.AuthorizationOutcome
import it.polito.cpo.client.ConfirmReservationOutcome
import it.polito.cpo.client.HoldOutcome
import it.polito.cpo.client.InventoryClient
import it.polito.cpo.client.InventoryRefusalCode
import it.polito.cpo.client.PaymentClient
import it.polito.cpo.client.RefundReservationOutcome
import it.polito.cpo.client.ReversalOutcome
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationStatus
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.OrderRepository
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReconciliationRecordRepository
import it.polito.cpo.service.RefundOrchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test of the post-sale refund (ADR-015), with real infrastructure: a
 * Testcontainers Postgres (R2DBC at runtime + Flyway through V15) and an embedded Kafka broker. Only
 * Payments and Inventory are replaced by controllable in-process doubles, exactly as
 * [CheckoutPipelineIntegrationTest] replaces them for the checkout pipeline.
 *
 * Orders are seeded directly as already-`CONFIRMED` rows rather than driven through a full checkout:
 * the checkout pipeline itself is [CheckoutPipelineIntegrationTest]'s concern, and what this test
 * needs is a `CONFIRMED` order carrying frozen refund terms, which seeding gives directly and quickly.
 *
 * As with checkout, `refund()` no longer publishes to Kafka directly: it writes a `refund-requested`
 * row into `outbox_events` in the same transaction as the `REFUNDING` commit (Debezium relays it in
 * production). [relayRefundRequested] stands in for Debezium.
 */
@SpringBootTest(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
@EmbeddedKafka(
    partitions = 1,
    topics = ["order-placed", "order-placed-dlt", "order-confirmed", "notification-requested", "refund-requested", "refund-requested-dlt"],
)
@Testcontainers
@Import(RefundPipelineIntegrationTest.TestDoubles::class)
class RefundPipelineIntegrationTest {

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

    @Autowired private lateinit var refundOrchestrator: RefundOrchestrator
    @Autowired private lateinit var orderRepository: OrderRepository
    @Autowired private lateinit var outboxRepository: OutboxEventRepository
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var inventory: ControllableRefundInventoryClient
    @Autowired private lateinit var payment: ControllableRefundPaymentClient
    @Autowired private lateinit var reconciliationRepository: ReconciliationRecordRepository
    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Test
    fun `a refundable confirmed order is refunded end to end through the outbox and Kafka`() = runBlocking {
        val orderId = seedConfirmedOrder()

        val accepted = refundOrchestrator.refund(orderId, "it-refund-${UUID.randomUUID()}", "user-1", isStaff = false)
        assertEquals(OrderStatus.REFUNDING, accepted.status)

        val outboxRows = outboxRows(orderId, "refund-requested")
        assertEquals(1, outboxRows.size)

        relayRefundRequested(orderId)

        val order = awaitStatus(orderId, OrderStatus.REFUNDED)
        assertEquals("prov-ref-it", order.refundProviderReference)
        assertNotNull(order.refundedAt)

        assertTrue(payment.refundedOrderIds.contains(orderId))
        assertTrue(payment.refundIdempotencyKeys.contains("pay-refund-$orderId"))
        // Never the compensation key: reusing it would replay compensation's stored result at
        // Payments (200, no money moved) instead of performing this commercial refund (ADR-015).
        assertTrue(payment.refundIdempotencyKeys.none { it == "refund-$orderId" })
        assertTrue(inventory.refundedReservationIds.contains("res-$orderId"))
    }

    @Test
    fun `a refund past the cutoff is refused before anything is committed`() = runBlocking {
        val orderId = seedConfirmedOrder(departureAt = OffsetDateTime.now().minusMinutes(1))

        val ex = assertFailsWith<ApiException> {
            refundOrchestrator.refund(orderId, "it-refund-late-${UUID.randomUUID()}", "user-1", isStaff = false)
        }
        assertEquals("REFUND_CUTOFF_PASSED", ex.code)

        assertEquals(OrderStatus.CONFIRMED, orderRepository.findById(orderId)!!.status)
        assertTrue(outboxRows(orderId, "refund-requested").isEmpty())
    }

    @Test
    fun `a seat-return failure leaves the order REFUNDING with an open SEAT_RETURN_FAILURE record`() = runBlocking {
        val orderId = seedConfirmedOrder()
        inventory.refuseWith[orderId] = InventoryRefusalCode.RESERVATION_SEAT_CONFLICT

        refundOrchestrator.refund(orderId, "it-refund-seatfail-${UUID.randomUUID()}", "user-1", isStaff = false)
        relayRefundRequested(orderId)

        awaitCondition {
            reconciliationRepository.findAllByOrderIdAndStatus(orderId, ReconciliationStatus.OPEN.name).toList().isNotEmpty()
        }

        val order = orderRepository.findById(orderId)!!
        assertEquals(OrderStatus.REFUNDING, order.status) // never a terminal failure (ADR-015)
        assertNull(order.refundedAt)

        val open = reconciliationRepository.findAllByOrderIdAndStatus(orderId, ReconciliationStatus.OPEN.name).toList()
        assertEquals(1, open.size)
        assertEquals(ReconciliationKind.SEAT_RETURN_FAILURE.name, open.single().kind)
    }

    // --- helpers ------------------------------------------------------------------------------------

    private suspend fun seedConfirmedOrder(
        offerId: String = "of-002",
        refundable: Boolean = true,
        refundCutoffMinutesBeforeDeparture: Int = 60,
        departureAt: OffsetDateTime = OffsetDateTime.now().plusDays(1),
    ): UUID {
        val orderId = UUID.randomUUID()
        orderRepository.save(
            it.polito.cpo.model.Order(
                id = orderId,
                userId = "user-1",
                status = OrderStatus.CONFIRMED,
                routeId = "R1",
                seats = "1A",
                travelDate = LocalDate.now().plusDays(1),
                totalAmount = BigDecimal("49.90"),
                currency = "EUR",
                createdAt = LocalDateTime.now(),
                reservationId = "res-$orderId",
                offerId = offerId,
                refundable = refundable,
                refundCutoffMinutesBeforeDeparture = refundCutoffMinutesBeforeDeparture,
                offerTermsVersion = 1,
                departureAt = departureAt,
            ).apply { setAsNew(true) },
        )
        return orderId
    }

    // Stands in for Debezium: reads the committed refund-requested outbox row for this order and
    // publishes the JSON payload to Kafka, keyed by aggregate id (the order id).
    private suspend fun relayRefundRequested(orderId: UUID) {
        outboxRows(orderId, "refund-requested")
            .forEach { kafkaTemplate.send("refund-requested", it.aggregateId, it.payload.asString()) }
    }

    private suspend fun outboxRows(orderId: UUID, type: String) =
        outboxRepository.findAll().toList().filter { it.aggregateId == orderId.toString() && it.type == type }

    private suspend fun awaitStatus(id: UUID, status: OrderStatus) = withTimeout(30_000) {
        var order = orderRepository.findById(id)
        while (order == null || order.status != status) {
            delay(200)
            order = orderRepository.findById(id)
        }
        order
    }

    private suspend fun awaitCondition(check: suspend () -> Boolean) = withTimeout(30_000) {
        while (!check()) delay(200)
    }

    /**
     * Controllable doubles for Payments and Inventory, keyed by order id like
     * [CheckoutPipelineIntegrationTest]'s. `refundReservation` derives the reservation id the same way
     * [seedConfirmedOrder] does (`res-{orderId}`), which is all this test needs since it never drives
     * a real hold.
     */
    class ControllableRefundPaymentClient : PaymentClient {
        val refundedOrderIds = CopyOnWriteArrayList<UUID>()
        val refundIdempotencyKeys = CopyOnWriteArrayList<String>()

        override suspend fun authorizePayment(request: PaymentRequest, idempotencyKey: String, correlationId: String): AuthorizationOutcome =
            throw UnsupportedOperationException("not used by the refund pipeline")

        override suspend fun refund(orderId: UUID, idempotencyKey: String): ReversalOutcome {
            refundedOrderIds.add(orderId)
            refundIdempotencyKeys.add(idempotencyKey)
            return ReversalOutcome.Reversed(providerReference = "prov-ref-it")
        }
    }

    class ControllableRefundInventoryClient : InventoryClient {
        val refundedReservationIds = CopyOnWriteArrayList<String>()

        /** orderId -> a definitive refusal to answer the seat return with. */
        val refuseWith = ConcurrentHashMap<UUID, InventoryRefusalCode>()

        override suspend fun reserveSeats(request: ReservationRequest, idempotencyKey: String, userId: String): HoldOutcome =
            throw UnsupportedOperationException("not used by the refund pipeline")

        override suspend fun confirmReservation(reservationId: String, orderId: UUID): ConfirmReservationOutcome =
            throw UnsupportedOperationException("not used by the refund pipeline")

        override suspend fun releaseSeats(reservationId: String, idempotencyKey: String) =
            throw UnsupportedOperationException("not used by the refund pipeline")

        override suspend fun refundReservation(reservationId: String, orderId: UUID): RefundReservationOutcome {
            refuseWith[orderId]?.let { return RefundReservationOutcome.Refused(it, "forced by test") }
            refundedReservationIds.add(reservationId)
            return RefundReservationOutcome.Refunded(reservationId)
        }
    }

    @TestConfiguration
    class TestDoubles {
        @Bean @Primary fun inventoryDouble() = ControllableRefundInventoryClient()

        @Bean @Primary fun paymentDouble() = ControllableRefundPaymentClient()

        // No real Keycloak in the test: back off the network-fetching decoder. It is never invoked
        // because this test drives the orchestrator directly rather than over HTTP.
        @Bean @Primary fun testJwtDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { Mono.empty() }
    }
}
