package it.polito.cpo.integration

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.micrometer.core.instrument.MeterRegistry
import io.r2dbc.spi.R2dbcException
import it.polito.cpo.client.AuthorizationOutcome
import it.polito.cpo.client.CatalogClient
import it.polito.cpo.client.ConfirmReservationOutcome
import it.polito.cpo.client.HoldOutcome
import it.polito.cpo.client.InventoryClient
import it.polito.cpo.client.InventoryRefusalCode
import it.polito.cpo.client.OfferOutcome
import it.polito.cpo.client.PaymentClient
import it.polito.cpo.client.RefundReservationOutcome
import it.polito.cpo.client.ResolvedOffer
import it.polito.cpo.client.ReversalOutcome
import it.polito.cpo.client.RouteOutcome
import it.polito.cpo.client.RouteSchedule
import it.polito.cpo.config.DEAD_LETTER_TOPICS
import it.polito.cpo.config.EXHAUSTABLE_TOPICS
import it.polito.cpo.contracts.catalog.RefundPolicy
import it.polito.cpo.contracts.events.OrderPlacedEvent
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.controller.dtos.CheckoutRequest
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.OutboxEvent
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationStatus
import it.polito.cpo.repository.OrderRepository
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReconciliationRecordRepository
import it.polito.cpo.controller.dtos.ResolveReconciliationRequest
import it.polito.cpo.observability.ApiException
import it.polito.cpo.service.AuditLogCapture
import it.polito.cpo.service.CheckoutOrchestrator
import it.polito.cpo.service.FailureClassifier
import it.polito.cpo.service.ReconciliationOperationsService
import it.polito.cpo.service.ReconciliationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.NonTransientDataAccessResourceException
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ErrorHandlingUtils
import org.springframework.kafka.listener.RetryListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        // Tracing on, export off: @AutoConfigureTracing re-enables the W3C propagators the test
        // context otherwise disables (which left every outbox traceparent null), and this keeps the
        // OTLP exporter from reaching for a collector that is not there.
        "management.tracing.export.otlp.enabled=false",
        // The dead-letter consumer retries without limit; short intervals keep that test fast.
        "orders.kafka.dlt-retry.initial-interval-ms=50",
        "orders.kafka.dlt-retry.max-interval-ms=200",
        // The same for the checkout listener's dependency budget: minutes in production, seconds here.
        "orders.kafka.retry.dependency.initial-interval-ms=50",
        "orders.kafka.retry.dependency.max-interval-ms=200",
        "orders.kafka.retry.dependency.max-retries=10",
    ],
)
@AutoConfigureTracing
@EmbeddedKafka(
    partitions = 1,
    topics = ["order-placed", "order-placed-dlt", "order-confirmed", "notification-requested"],
)
@Testcontainers
@Import(CheckoutPipelineIntegrationTest.TestDoubles::class)
class CheckoutPipelineIntegrationTest {

    companion object {
        const val DECLINE_TOKEN = "DECLINE"

        /** W3C trace context: version 00, 32-hex trace id, 16-hex span id, flags. */
        val TRACEPARENT = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")

        /** A stable future date: the checkout guard rejects anything in the past. */
        val TRAVEL_DATE: LocalDate = LocalDate.now().plusDays(7)

        /** Priced at the 100.00 every request here asks for, and refundable so the terms are visible. */
        val OFFER = ResolvedOffer(
            offerId = "of-001",
            routeId = "R1",
            price = BigDecimal("100.00"),
            currency = "EUR",
            refundPolicy = RefundPolicy(refundable = true, cutoffMinutesBeforeDeparture = 60),
            termsVersion = 2,
            departureTime = LocalTime.of(7, 0),
            departureZone = ZoneId.of("Europe/Rome"),
        )

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
    @Autowired private lateinit var producerFactory: ProducerFactory<String, String>

    /**
     * Relays like Debezium does: no producer span of its own, and the row's `traceparent` copied into
     * the record header. The application template has producer observation on, which would replace
     * that header with a span of the relay's — something Debezium never adds.
     */
    private val relayTemplate by lazy { KafkaTemplate(producerFactory) }
    @Autowired private lateinit var inventory: ControllableInventoryClient
    @Autowired private lateinit var payment: ControllablePaymentClient
    @Autowired private lateinit var catalog: ControllableCatalogClient
    @Autowired private lateinit var reconciliationRepository: ReconciliationRecordRepository
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var meterRegistry: MeterRegistry
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var operations: ReconciliationOperationsService
    @Autowired private lateinit var reconciliationService: ReconciliationService
    @Autowired private lateinit var listenerRegistry: KafkaListenerEndpointRegistry
    @Autowired private lateinit var kafkaErrorHandler: DefaultErrorHandler

    @Test
    fun `happy path drives the order through the outbox and Kafka to CONFIRMED`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-happy-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        assertEquals(OrderStatus.ACCEPTED, response.status)

        relayOrderPlaced(response.orderId)

        val order = awaitTerminal(response.orderId)
        assertEquals(OrderStatus.CONFIRMED, order.status)
        assertTrue(payment.authorizedOrderIds.contains(response.orderId))
        assertFalse(inventory.releasedReservationIds.contains("res-${response.orderId}")) // no compensation
        // #117 break A: the hold ran with the Kafka consumer's observation as its parent.
        assertEquals("KafkaRecordReceiverContext", inventory.holdParentObservation[response.orderId])

        // #117 break B: every row carries a W3C traceparent, and the rows the consumer wrote inside its
        // transaction share the accept's trace — the header was read, and the context survived the
        // transaction hop into executeAndAwait.
        val rows = listOf("order-placed", "order-confirmed", "notification-requested")
            .map { type -> outboxRows(response.orderId, type).single() }
        rows.forEach { assertTrue(TRACEPARENT.matches(it.traceparent.orEmpty()), "${it.type}: ${it.traceparent}") }
        assertEquals(1, rows.map { it.traceparent!!.split("-")[1] }.toSet().size, rows.map { it.traceparent }.toString())
    }

    @Test
    fun `a checkout leaves one audit trail from the accept to the consumer's outcome, under one correlation id`() = runBlocking {
        AuditLogCapture().use { audit ->
            val response = orchestrator.checkout(request(), "it-audit-${UUID.randomUUID()}", "user-1", "user-1@example.com")
            relayOrderPlaced(response.orderId)
            assertEquals(OrderStatus.CONFIRMED, awaitTerminal(response.orderId).status)

            // The consumer writes its line after the commit awaitTerminal observes.
            val lines = awaitAuditLines(audit, response.orderId, 2)
            assertEquals(listOf("checkout" to "accepted", "checkout.pipeline" to "confirmed"), lines.map { it.action to it.outcome })
            lines.forEach { assertEquals("user-1", it.fields["principalId"]) }
            // The pipeline's line carries the id the accept put on the event, not the consumer's own.
            assertNotNull(lines[0].correlationId)
            assertEquals(lines[0].correlationId, lines[1].correlationId)
        }
    }

    @Test
    fun `a declined checkout is audited as failed by the consumer`() = runBlocking {
        AuditLogCapture().use { audit ->
            val response = orchestrator.checkout(request(token = DECLINE_TOKEN), "it-audit-decline-${UUID.randomUUID()}", "user-1", "user-1@example.com")
            relayOrderPlaced(response.orderId)
            assertEquals(OrderStatus.FAILED, awaitTerminal(response.orderId).status)

            val failed = awaitAuditLines(audit, response.orderId, 2)[1]
            assertEquals("checkout.pipeline" to "failed", failed.action to failed.outcome)
            assertEquals(FailureClassifier.PAYMENT_DECLINED, failed.fields["reason"])
        }
    }

    @Test
    fun `an offer checkout authorizes the resolved price and freezes its terms on the row`() = runBlocking {
        // Issue #93 end to end, through the real schema: the amount Payments is asked to authorize
        // has to be the one Catalog priced, not the one the browser sent, and the refund terms have
        // to be on the committed row where the refund can read them without calling Catalog.
        val response = orchestrator.checkout(
            // Same value, looser scale — a client is not required to send trailing zeros.
            // Loose scale on purpose — a client is not required to send trailing zeros — and two
            // seats, so the amount is the line total rather than the per-seat price.
            request(totalAmount = BigDecimal("200.0"), offerId = OFFER.offerId),
            "it-offer-${UUID.randomUUID()}",
            "user-1",
            "user-1@example.com",
        )
        relayOrderPlaced(response.orderId)
        val order = awaitTerminal(response.orderId)
        assertEquals(OrderStatus.CONFIRMED, order.status)

        val authorized = payment.authorizeRequests.single { it.orderId == response.orderId }
        assertEquals(0, BigDecimal("200.00").compareTo(authorized.amount))
        assertEquals("EUR", authorized.currency)

        // And the sale's terms survived the round-trip through V14.
        assertEquals(OFFER.offerId, order.offerId)
        assertEquals(true, order.refundable)
        assertEquals(60, order.refundCutoffMinutesBeforeDeparture)
        assertEquals(2, order.offerTermsVersion)
        assertEquals(
            TRAVEL_DATE.atTime(LocalTime.of(7, 0)).atZone(ZoneId.of("Europe/Rome")).toInstant(),
            order.departureAt!!.toInstant(),
        )
    }

    @Test
    fun `a checkout priced below the offer never becomes an order`() = runBlocking {
        // The bug as it was reachable before this: one cent, authorized and confirmed as a sale.
        // The rejection has to happen in the synchronous entry, so nothing is persisted and the
        // customer can correct it — a FAILED order found minutes later is not correctable.
        val key = "it-underpriced-${UUID.randomUUID()}"

        val ex = assertFailsWith<ApiException> {
            orchestrator.checkout(
                request(totalAmount = BigDecimal("0.01"), offerId = OFFER.offerId),
                key,
                "user-1",
                "user-1@example.com",
            )
        }

        assertEquals("OFFER_PRICE_MISMATCH", ex.code)
        assertTrue(
            outboxRepository.findAll().toList().none { it.payload.asString().contains(key) },
            "a rejected checkout must leave no outbox row",
        )
    }

    @Test
    fun `an unreachable Catalog now stops every checkout, not only the ones carrying an offer`() = runBlocking {
        // The availability trade got wider when `offerId` became required. It used to be "checkouts
        // carrying an offer stop, offer-less ones keep working", and that escape hatch is gone:
        // every checkout resolves, so Catalog is now on the critical path of all of them. Worth
        // asserting rather than assuming, because it is the cost of closing #93 and it should not be
        // discovered during an outage.
        catalog.failWith = IOException("catalog is down")
        try {
            val ex = assertFailsWith<ApiException> {
                orchestrator.checkout(
                    request(),
                    "it-catalog-down-${UUID.randomUUID()}",
                    "user-1",
                    "user-1@example.com",
                )
            }
            // Fail closed, and say which kind of failure it is: 503 means "ask again", where a 400
            // would have blamed a request that was perfectly correct.
            assertEquals("OFFER_UNRESOLVABLE", ex.code)
        } finally {
            catalog.failWith = null
        }
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
        val cancelled = orchestrator.cancel(response.orderId, "user-1")
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
    fun `a Payments outage longer than the default retry budget converges without an incident`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-outage-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val exhaustedBefore = exhaustedCount("order-placed", FailureClassifier.DEPENDENCY_ERROR)
        // Six refusals: the default budget (four deliveries) would have dead-lettered this order on
        // AKS, where an open breaker makes every attempt fail at once (configuration #55).
        payment.unavailableFor[response.orderId] = AtomicInteger(6)

        relayOrderPlaced(response.orderId)

        assertEquals(OrderStatus.CONFIRMED, awaitTerminal(response.orderId).status)
        assertEquals(1, payment.authorizedOrderIds.count { it == response.orderId })
        assertTrue(openRecords(response.orderId).isEmpty())
        assertEquals(exhaustedBefore, exhaustedCount("order-placed", FailureClassifier.DEPENDENCY_ERROR))
    }

    @Test
    fun `a PostgreSQL resource failure after the hold recovers beyond the old delivery budget`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-pg-recovery-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        val exhaustedBefore = exhaustedCount("order-placed", FailureClassifier.DEPENDENCY_ERROR)
        // PostgreSQL itself raises SQLSTATE 08006 at the Orders write after Inventory has held the
        // seats. This tests driver/Spring translation and the real Kafka error handler, not physical
        // connection loss or CNPG promotion. Six failed deliveries exceed the old four-delivery budget.
        armHoldWriteFailures(orderId, failures = 6)
        val failures = CopyOnWriteArrayList<Exception>()
        kafkaErrorHandler.setRetryListeners(RetryListener { record, exception, _ ->
            if (record.key() == orderId.toString()) failures.add(exception)
        })
        try {
            relayOrderPlaced(orderId) // One relay only: all subsequent attempts belong to Kafka.
            val reserving = awaitStatus(orderId, OrderStatus.RESERVING)
            assertEquals(null, reserving.reservationId)
            assertFalse(payment.authorizedOrderIds.contains(orderId))

            val terminal = awaitTerminal(orderId)
            assertEquals(OrderStatus.CONFIRMED, terminal.status)
            assertEquals("res-$orderId", terminal.reservationId)
            assertEquals(7, inventory.reservedOrderIds.count { it == orderId })
            assertEquals(6, failures.size)
            failures.forEach { failure ->
                val cause = ErrorHandlingUtils.findRootCause(failure)
                assertTrue(cause is NonTransientDataAccessResourceException, "Unexpected translated failure: $cause")
                val driverFailure = generateSequence<Throwable>(cause) { it.cause }.filterIsInstance<R2dbcException>().first()
                assertEquals("08006", driverFailure.sqlState)
            }
            assertEquals(List(7) { "inv-$orderId" }, inventory.holdKeys.filter { it.first == orderId }.map { it.second })
            assertEquals(1, inventory.holdsByKey.values.count { it.reservationId == "res-$orderId" })
            assertEquals(1, payment.authorizedOrderIds.count { it == orderId })
            assertEquals(listOf("pay-$orderId"), payment.authorizeKeys.filter { it.first == orderId }.map { it.second })
            assertFalse(inventory.releasedReservationIds.contains("res-$orderId"))
            assertFalse(payment.refundedOrderIds.contains(orderId))
            assertTrue(openRecords(orderId).isEmpty())
            assertEquals(exhaustedBefore, exhaustedCount("order-placed", FailureClassifier.DEPENDENCY_ERROR))
            assertEquals(1, outboxRows(orderId, "order-confirmed").size)
            assertEquals(1, outboxRows(orderId, "notification-requested").size)
        } finally {
            kafkaErrorHandler.setRetryListeners()
            exec("DROP TRIGGER IF EXISTS test_fail_hold_write ON orders")
        }
    }

    @Test
    fun `the operator list puts a live seat hold ahead of an expired one, with its deadline and status`() = runBlocking {
        // Opened first, so an oldest-first list would show it first: an expired PAYMENT_PENDING, the
        // kind of incident nothing closes.
        val expired = orchestrator.checkout(request(), "it-list-old-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(expired.orderId, OrderStatus.PAYMENT_PENDING, "res-${expired.orderId}")
        seedHoldExpiry(expired.orderId, OffsetDateTime.now().minusMinutes(30))
        openIncident(expired.orderId, ReconciliationKind.DELIVERY_EXHAUSTED)

        val live = orchestrator.checkout(request(), "it-list-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(live.orderId, OrderStatus.PAYMENT_PENDING, "res-${live.orderId}")
        val expiresAt = OffsetDateTime.now().plusMinutes(7).withNano(0)
        seedHoldExpiry(live.orderId, expiresAt)
        openIncident(live.orderId, ReconciliationKind.DELIVERY_EXHAUSTED)

        val ids = operations.listOpen(limit = 500).map { it.orderId }
        assertTrue(ids.indexOf(live.orderId) in 0 until ids.indexOf(expired.orderId), ids.toString())

        val listed = operations.listOpen(limit = 500).single { it.orderId == live.orderId }
        assertEquals(ReconciliationKind.DELIVERY_EXHAUSTED.name, listed.kind)
        assertEquals(OrderStatus.PAYMENT_PENDING.name, listed.orderState)
        assertEquals(OrderStatus.PAYMENT_PENDING.name, listed.orderStatus)
        assertEquals(expiresAt.toInstant(), listed.reservationExpiresAt?.toInstant())
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
        // Checkout compensation must use its own idempotency namespace, never the post-sale refund's
        // pay-refund-{orderId} (ADR-015): the two must not alias, or Payments could replay one
        // reversal's stored result for the other.
        assertTrue(payment.refundIdempotencyKeys.contains("refund-${response.orderId}"))
        assertFalse(payment.refundIdempotencyKeys.contains("pay-refund-${response.orderId}"))
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

    // --- travel date (issue #64) ------------------------------------------------------------------

    @Test
    fun `the same seat on the same route can be sold for two different days`() = runBlocking {
        val firstDay = TRAVEL_DATE
        val secondDay = TRAVEL_DATE.plusDays(1)
        val seat = "7C"

        val first = orchestrator.checkout(
            request(travelDate = firstDay, seats = listOf(seat), totalAmount = BigDecimal("100.00")),
            "it-date-a-${UUID.randomUUID()}", "user-1", "user-1@example.com",
        )
        val second = orchestrator.checkout(
            request(travelDate = secondDay, seats = listOf(seat), totalAmount = BigDecimal("100.00")),
            "it-date-b-${UUID.randomUUID()}", "user-1", "user-1@example.com",
        )

        relayOrderPlaced(first.orderId)
        relayOrderPlaced(second.orderId)

        // This is the issue: before the travel-date dimension existed, the second purchase could not
        // succeed, because the seat was keyed by (route, seat) alone and the first sale consumed it
        // for every future day.
        assertEquals(OrderStatus.CONFIRMED, awaitTerminal(first.orderId).status)
        assertEquals(OrderStatus.CONFIRMED, awaitTerminal(second.orderId).status)

        // Two distinct holds, differing only by the day.
        assertTrue(inventory.heldTriples.contains(Triple("R1", firstDay, seat)))
        assertTrue(inventory.heldTriples.contains(Triple("R1", secondDay, seat)))
    }

    @Test
    fun `the travel date survives the round trip through the database`() = runBlocking {
        val date = TRAVEL_DATE.plusDays(3)

        val response = orchestrator.checkout(
            request(travelDate = date), "it-date-rt-${UUID.randomUUID()}", "user-1", "user-1@example.com",
        )

        // Read back through R2DBC rather than from the in-memory object: a DATE column mapped to the
        // wrong type is the kind of thing only a real round trip catches.
        assertEquals(date, orderRepository.findById(response.orderId)!!.travelDate)

        relayOrderPlaced(response.orderId)
        assertEquals(OrderStatus.CONFIRMED, awaitTerminal(response.orderId).status)
        // And the pipeline passed the persisted date on, not a recomputed one.
        assertTrue(inventory.heldTriples.any { it.second == date })
    }

    // --- dead-letter handling ---------------------------------------------------------------------

    @Test
    fun `an unparseable order-placed payload reaches the dead-letter topic`() = runBlocking {
        val before = dltCount("poison")
        val exhaustedBefore = exhaustedCount("order-placed", FailureClassifier.POISON_PAYLOAD)
        // Scoped to the delta, not to an absolute count: sibling tests in this shared context create
        // records of their own.
        val recordsBefore = reconciliationRepository.findAll().toList().size

        // Proves the whole chain end to end: PoisonEventException, the non-retryable classification,
        // the recoverer, and the dead-letter listener's own poison branch.
        kafkaTemplate.send("order-placed", "not-an-order", "{broken")

        awaitDltCount("poison", before + 1)
        // No order id means no incident can be attached to anything.
        assertEquals(recordsBefore, reconciliationRepository.findAll().toList().size)
        // F4: the real error handler counted it where the delivery was abandoned, not where the DLT
        // was read. Awaited, not asserted: the count lands after the broker acknowledges the dead
        // letter, which can be after the DLT listener has already counted it.
        withTimeout(30_000) {
            while (exhaustedCount("order-placed", FailureClassifier.POISON_PAYLOAD) < exhaustedBefore + 1) delay(100)
        }
    }

    @Test
    fun `the checkout-outcome series are registered at startup, before any checkout`() {
        // Only presence: siblings in this shared context have already counted into some of them.
        listOf("confirmed", "failed").forEach {
            assertNotNull(meterRegistry.find("orders.checkout.completed").tag("result", it).counter(), it)
        }
        FailureClassifier.CHECKOUT_FAILURE_REASONS.forEach {
            assertNotNull(meterRegistry.find("orders.checkout.failed").tag("reason", it).counter(), it)
        }
    }

    @Test
    fun `the dead-letter series are registered at startup, before any dead letter`() {
        // Only presence: siblings in this shared context may already have counted into some of them.
        EXHAUSTABLE_TOPICS.forEach { topic ->
            FailureClassifier.DEAD_LETTER_LABELS.forEach { label ->
                assertNotNull(
                    meterRegistry.find("orders.events.exhausted").tags("topic", topic, "failure_class", label).counter(),
                    "$topic/$label",
                )
            }
        }
    }

    @Test
    fun `a dead letter whose incident cannot be written is retried until it is, never dropped`() = runBlocking {
        val key = "it-dlt-retry-${UUID.randomUUID()}"
        val response = orchestrator.checkout(request(), key, "user-1", "user-1@example.com")
        seedState(response.orderId, OrderStatus.CONFIRMING_INVENTORY, "res-${response.orderId}")
        val failedBefore = deadLetterRecordFailed()

        // Four failed writes: one more than the bounded back-off this consumer used to have tried
        // before logging the dead letter, committing it, and losing the incident.
        armIncidentWriteFailures(response.orderId, failures = 4)
        try {
            sendDeadLetter(response.orderId, "java.util.concurrent.TimeoutException", "simulated")
            withTimeout(30_000) { while (openRecords(response.orderId).isEmpty()) delay(100) }
        } finally {
            disarmIncidentWriteFailures()
        }

        assertEquals(ReconciliationKind.DELIVERY_EXHAUSTED.name, openRecords(response.orderId).single().kind)
        // Every failed attempt is visible, so an incident that cannot be written can alert.
        val failedAfter = deadLetterRecordFailed()
        assertTrue(failedAfter >= failedBefore + 4, "record_failed rose from $failedBefore only to $failedAfter")
    }

    @Test
    fun `an oversized client correlation id cannot stall the dead-letter consumer`() = runBlocking {
        // X-Correlation-Id is accepted at any length; the incident column holds 255. Untruncated, the
        // write would fail forever and, with unbounded retry, hold up the whole DLT stream.
        val key = "it-dlt-longcorr-${UUID.randomUUID()}"
        val response = orchestrator.checkout(request(), key, "user-1", "user-1@example.com")
        seedState(response.orderId, OrderStatus.CONFIRMING_INVENTORY, "res-${response.orderId}")
        val oversized = "c".repeat(300)

        sendDeadLetter(response.orderId, "java.util.concurrent.TimeoutException", "simulated", correlationId = oversized)
        withTimeout(30_000) { while (openRecords(response.orderId).isEmpty()) delay(100) }

        assertEquals(oversized.take(255), openRecords(response.orderId).single().correlationId)
    }

    @Test
    fun `the dead-letter record_failed series are registered at startup`() {
        DEAD_LETTER_TOPICS.forEach {
            assertNotNull(
                meterRegistry.find("orders.events.dlt").tags("topic", it, "result", "record_failed").counter(),
                it,
            )
        }
    }

    @Test
    fun `every listener is configured to stop after the record in progress, not after its batch`() {
        // The pipeline and the dead-letter listeners alike: a slow dependency multiplied by a whole
        // polled batch would outlast the shutdown phase and the pod's grace period. The behaviour
        // itself is proven on compose (docs/operations/orders-shutdown-and-replay.md).
        val containers = listenerRegistry.allListenerContainers
        assertEquals(4, containers.size, "two pipeline and two dead-letter listeners")
        containers.forEach { assertTrue(it.containerProperties.isStopImmediate, it.listenerId.toString()) }
    }

    @Test
    fun `an exhausted delivery opens a DELIVERY_EXHAUSTED incident without moving the order`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-dlt-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(response.orderId, OrderStatus.CONFIRMING_INVENTORY, "res-${response.orderId}")
        val before = dltCount("recorded")

        sendDeadLetter(response.orderId, "java.util.concurrent.TimeoutException", "simulated")

        awaitDltCount("recorded", before + 1)
        val open = openRecords(response.orderId)
        assertEquals(1, open.size)
        assertEquals(ReconciliationKind.DELIVERY_EXHAUSTED.name, open.single().kind)
        // Classified from the cause header, not the wrapper: a timeout is a dependency error.
        assertEquals(FailureClassifier.DEPENDENCY_ERROR, open.single().failureClass)
        // The durable phase is what a later redrive resumes from, so the incident must not disturb it.
        assertEquals(OrderStatus.CONFIRMING_INVENTORY, orderRepository.findById(response.orderId)!!.status)
        assertEquals(OrderStatus.CONFIRMING_INVENTORY.name, open.single().orderState)
        // Structurally impossible for this listener, asserted anyway because it is the whole point.
        assertFalse(payment.refundedOrderIds.contains(response.orderId))
        assertFalse(inventory.releasedReservationIds.contains("res-${response.orderId}"))
    }

    @Test
    fun `a repeated dead letter bumps the attempt count instead of duplicating the incident`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-dlt2-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(response.orderId, OrderStatus.PAYMENT_PENDING, "res-${response.orderId}")
        val before = dltCount("recorded")

        sendDeadLetter(response.orderId, "java.util.concurrent.TimeoutException", "first")
        awaitDltCount("recorded", before + 1)
        sendDeadLetter(response.orderId, "java.util.concurrent.TimeoutException", "second")
        awaitDltCount("recorded", before + 2)

        val open = openRecords(response.orderId)
        assertEquals(1, open.size)
        assertEquals(2, open.single().attemptCount)
    }

    @Test
    fun `a dead letter for an order that later succeeded opens nothing and closes stale incidents`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-dlt3-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        relayOrderPlaced(response.orderId)
        assertEquals(OrderStatus.CONFIRMED, awaitTerminal(response.orderId).status)
        val before = dltCount("stale_terminal")

        sendDeadLetter(response.orderId, "java.util.concurrent.TimeoutException", "late duplicate")

        awaitDltCount("stale_terminal", before + 1)
        // Without this branch, healthy orders would accumulate permanently open incidents.
        assertTrue(openRecords(response.orderId).isEmpty())
    }

    @Test
    fun `a dead letter for an unknown order opens no incident`() = runBlocking {
        val unknown = UUID.randomUUID()
        val before = dltCount("unknown_order")

        sendDeadLetter(unknown, "java.util.concurrent.TimeoutException", "orphan")

        awaitDltCount("unknown_order", before + 1)
        assertTrue(openRecords(unknown).isEmpty())
    }

    // --- operator commands ------------------------------------------------------------------------

    @Test
    fun `a redrive writes a fresh order-placed row and the pipeline resumes from the stored phase`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-redrive-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        seedState(orderId, OrderStatus.CONFIRMING_INVENTORY, "res-$orderId")
        openIncident(orderId, ReconciliationKind.DELIVERY_EXHAUSTED)

        val accepted = operations.redrive(orderId, "redrive-$orderId", "ops-1")

        // The endpoint reports the phase the resumed pipeline will pick up from; it contains no logic
        // about which stage that is.
        assertEquals(OrderStatus.CONFIRMING_INVENTORY, accepted.orderStatus)
        assertEquals(2, accepted.attemptCount)
        assertEquals(2, outboxRows(orderId, "order-placed").size)

        relayLatestOrderPlaced(orderId)

        assertEquals(OrderStatus.CONFIRMED, awaitTerminal(orderId).status)
        // Resumed, not restarted: the hold and the authorization were not repeated.
        assertEquals(0, inventory.reservedOrderIds.count { it == orderId })
        assertEquals(0, payment.authorizedOrderIds.count { it == orderId })
        // Reaching a terminal state closes the incident by itself.
        assertTrue(openRecords(orderId).isEmpty())
    }

    @Test
    fun `repeating a redrive key replays the response without writing a second outbox row`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-redrive2-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        seedState(orderId, OrderStatus.CONFIRMING_INVENTORY, "res-$orderId")
        openIncident(orderId, ReconciliationKind.DELIVERY_EXHAUSTED)

        val first = operations.redrive(orderId, "redrive-dup-$orderId", "ops-1")
        val replay = operations.redrive(orderId, "redrive-dup-$orderId", "ops-1")

        assertEquals(first, replay)
        assertEquals(2, outboxRows(orderId, "order-placed").size) // the original plus one redrive
    }

    @Test
    fun `an operator's redrive is audited under the operator, naming the owner, and so is its refusal`() = runBlocking {
        val pending = orchestrator.checkout(request(), "it-audit-redrive-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(pending.orderId, OrderStatus.CONFIRMING_INVENTORY, "res-${pending.orderId}")
        openIncident(pending.orderId, ReconciliationKind.DELIVERY_EXHAUSTED)
        val confirmed = orchestrator.checkout(request(), "it-audit-redrive2-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        relayOrderPlaced(confirmed.orderId)
        assertEquals(OrderStatus.CONFIRMED, awaitTerminal(confirmed.orderId).status)

        AuditLogCapture().use { audit ->
            operations.redrive(pending.orderId, "redrive-audit-${pending.orderId}", "ops-1")
            assertFailsWith<ApiException> {
                operations.redrive(confirmed.orderId, "redrive-audit-${confirmed.orderId}", "ops-1")
            }

            val accepted = audit.forOrder(pending.orderId).single { it.action == "reconciliation.redrive" }
            assertEquals("accepted", accepted.outcome)
            assertEquals("ops-1", accepted.fields["principalId"])
            assertEquals("user-1", accepted.fields["ownerId"])
            val refused = audit.forOrder(confirmed.orderId).single { it.action == "reconciliation.redrive" }
            assertEquals("rejected", refused.outcome)
            assertEquals("ORDER_ALREADY_TERMINAL", refused.fields["reason"])
        }
    }

    @Test
    fun `a redrive on a terminal order is refused and closes the stale incident`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-redrive3-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        relayOrderPlaced(orderId)
        assertEquals(OrderStatus.CONFIRMED, awaitTerminal(orderId).status)
        openIncident(orderId, ReconciliationKind.DELIVERY_EXHAUSTED)

        val ex = assertFailsWith<ApiException> { operations.redrive(orderId, "redrive-term-$orderId", "ops-1") }

        assertEquals("ORDER_ALREADY_TERMINAL", ex.code)
        // Both halves must hold: the refusal AND the committed close. Throwing from inside the
        // transaction would have rolled the close back and left the record open forever.
        assertTrue(openRecords(orderId).isEmpty())
    }

    @Test
    fun `a redrive is refused when the hold behind a PAYMENT_PENDING order has already expired`() = runBlocking {
        // Inventory's sweeper expires the reservation and releases the seats; Orders is never told, so
        // the order sits in PAYMENT_PENDING pointing at a hold that no longer exists. PAYMENT_PENDING
        // resumes by authorizing, so redriving it would buy a charge and its reversal and nothing else.
        val response = orchestrator.checkout(request(), "it-redrive-exp-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        seedState(orderId, OrderStatus.PAYMENT_PENDING, "res-$orderId")
        seedHoldExpiry(orderId, OffsetDateTime.now().minusHours(2))
        openIncident(orderId, ReconciliationKind.DELIVERY_EXHAUSTED)

        val ex = assertFailsWith<ApiException> { operations.redrive(orderId, "redrive-exp-$orderId", "ops-1") }

        assertEquals("RESERVATION_HOLD_EXPIRED", ex.code)
        // Nothing was triggered, and -- unlike the terminal refusal -- the incident is NOT closed: the
        // order still needs an operator, and closing it here would hide the only record saying so.
        assertEquals(1, outboxRows(orderId, "order-placed").size)
        assertEquals(1, openRecords(orderId).size)
        assertEquals(0, payment.authorizedOrderIds.count { it == orderId })
    }

    @Test
    fun `a redrive of a PAYMENT_PENDING order whose hold is still live is accepted`() = runBlocking {
        // The negative half: the refusal is about an expired hold, not about PAYMENT_PENDING itself.
        val response = orchestrator.checkout(request(), "it-redrive-live-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        seedState(orderId, OrderStatus.PAYMENT_PENDING, "res-$orderId")
        seedHoldExpiry(orderId, OffsetDateTime.now().plusMinutes(5))
        openIncident(orderId, ReconciliationKind.DELIVERY_EXHAUSTED)

        val accepted = operations.redrive(orderId, "redrive-live-$orderId", "ops-1")

        assertEquals(OrderStatus.PAYMENT_PENDING, accepted.orderStatus)
        assertEquals(2, outboxRows(orderId, "order-placed").size)
    }

    @Test
    fun `an expired hold does not block a redrive from the phases that do not authorize`() = runBlocking {
        // The realistic way to get this wrong is to refuse every non-terminal phase. CONFIRMING_INVENTORY
        // has already taken the money, and redriving it is how the order reaches a resolution at all --
        // Inventory refuses the confirmation with RESERVATION_EXPIRED and the pipeline compensates.
        // Refusing here would strand the order instead of protecting it.
        val response = orchestrator.checkout(request(), "it-redrive-exp2-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        seedState(orderId, OrderStatus.CONFIRMING_INVENTORY, "res-$orderId")
        seedHoldExpiry(orderId, OffsetDateTime.now().minusHours(2))
        openIncident(orderId, ReconciliationKind.DELIVERY_EXHAUSTED)

        val accepted = operations.redrive(orderId, "redrive-exp2-$orderId", "ops-1")

        assertEquals(OrderStatus.CONFIRMING_INVENTORY, accepted.orderStatus)
        assertEquals(2, outboxRows(orderId, "order-placed").size)
    }

    // --- abandoning an expired checkout (#140) -----------------------------------------------------

    /** A PAYMENT_PENDING order past its hold with an exhausted delivery: what only an abandonment can end. */
    private suspend fun expiredPaymentPending(tag: String): UUID {
        val response = orchestrator.checkout(request(), "it-$tag-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        seedState(orderId, OrderStatus.PAYMENT_PENDING, "res-$orderId")
        seedHoldExpiry(orderId, OffsetDateTime.now().minusHours(2))
        openIncident(orderId, ReconciliationKind.DELIVERY_EXHAUSTED)
        return orderId
    }

    private suspend fun awaitOpenIncident(orderId: UUID, kind: ReconciliationKind) = withTimeout(30_000) {
        var record = openRecords(orderId).firstOrNull { it.kind == kind.name }
        while (record == null) {
            delay(100)
            record = openRecords(orderId).firstOrNull { it.kind == kind.name }
        }
        record
    }

    @Test
    fun `an abandoned checkout Payments has no authorization for waits on the operator, who settles it`() = runBlocking {
        // No row at Payments does not prove no charge: Payments calls the provider before writing it.
        val orderId = expiredPaymentPending("abandon")
        payment.noAuthorizationFor.add(orderId)

        val accepted = operations.abandon(orderId, "abandon-$orderId", "ops-1")
        assertEquals(OrderStatus.COMPENSATING, accepted.orderStatus)
        assertEquals(2, outboxRows(orderId, "order-placed").size)
        relayLatestOrderPlaced(orderId)

        val unknown = awaitOpenIncident(orderId, ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN)
        assertEquals(FailureClassifier.PAYMENT_OUTCOME_UNKNOWN, unknown.failureClass)
        // Not failed on a guess: the order waits, never authorized, its lost hold already released.
        assertEquals(OrderStatus.COMPENSATING, orderRepository.findById(orderId)!!.status)
        assertEquals(0, payment.authorizedOrderIds.count { it == orderId })
        assertTrue(inventory.releasedReservationIds.contains("res-$orderId"))
        assertEquals(0, outboxRows(orderId, "notification-requested").size)

        val settled = operations.settle(
            orderId, unknown.id, "settle-$orderId", ResolveReconciliationRequest("stripe: no charge for $orderId"), "ops-1",
        )
        assertEquals(OrderStatus.COMPENSATING, settled.orderStatus)
        relayLatestOrderPlaced(orderId)

        val order = awaitTerminal(orderId)
        assertEquals(OrderStatus.FAILED, order.status)
        assertEquals("CHECKOUT_FAILED", order.failureCode)
        assertEquals(0, payment.authorizedOrderIds.count { it == orderId })
        assertFalse(payment.reversedOrderIds.contains(orderId))
        assertEquals(1, outboxRows(orderId, "notification-requested").size)
        assertTrue(openRecords(orderId).isEmpty())
        val evidence = reconciliationRepository.findById(unknown.id)!!
        assertEquals("stripe: no charge for $orderId", evidence.evidenceReference)
        assertEquals("ops-1", evidence.resolvedBy)
    }

    @Test
    fun `an abandoned checkout whose authorization is not AUTHORIZED waits on the operator too`() = runBlocking {
        val orderId = expiredPaymentPending("abandon-declined")
        payment.unsettledAuthorizationFor.add(orderId)

        operations.abandon(orderId, "abandon-declined-$orderId", "ops-1")
        relayLatestOrderPlaced(orderId)

        awaitOpenIncident(orderId, ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN)
        assertEquals(OrderStatus.COMPENSATING, orderRepository.findById(orderId)!!.status)
        // Not the reversal-failure incident, which a redrive could never clear here.
        assertTrue(openRecords(orderId).none { it.kind == ReconciliationKind.PAYMENT_REVERSAL_FAILURE.name })
    }

    @Test
    fun `abandoning an expired checkout whose payment was authorized reverses it, then fails it`() = runBlocking {
        val orderId = expiredPaymentPending("abandon-paid")

        operations.abandon(orderId, "abandon-paid-$orderId", "ops-1")
        relayLatestOrderPlaced(orderId)

        assertEquals(OrderStatus.FAILED, awaitTerminal(orderId).status)
        // Reversed under the checkout compensation key, never authorized again, no operator needed.
        assertTrue(payment.refundIdempotencyKeys.contains("refund-$orderId"))
        assertTrue(payment.reversedOrderIds.contains(orderId))
        assertEquals(0, payment.authorizedOrderIds.count { it == orderId })
        assertTrue(reconciliationRepository.findAll().toList().none {
            it.orderId == orderId && it.kind == ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN.name
        })
        assertTrue(openRecords(orderId).isEmpty())
    }

    /** An abandoned order parked on PAYMENT_OUTCOME_UNKNOWN, as Payments first answers "no authorization". */
    private suspend fun parkedOnUnknownOutcome(tag: String): Pair<UUID, UUID> {
        val orderId = expiredPaymentPending(tag)
        payment.noAuthorizationFor.add(orderId)
        operations.abandon(orderId, "abandon-$tag-$orderId", "ops-1")
        relayLatestOrderPlaced(orderId)
        return orderId to awaitOpenIncident(orderId, ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN).id
    }

    @Test
    fun `a late authorization found by a redrive is reversed and closes the unknown outcome by itself`() = runBlocking {
        val (orderId, recordId) = parkedOnUnknownOutcome("late-redrive")
        // Payments records the authorization after all, before anyone settles.
        payment.noAuthorizationFor.remove(orderId)

        operations.redrive(orderId, "redrive-late-$orderId", "ops-1")
        relayLatestOrderPlaced(orderId)

        assertEquals(OrderStatus.FAILED, awaitTerminal(orderId).status)
        assertTrue(payment.reversedOrderIds.contains(orderId))
        val closed = reconciliationRepository.findById(recordId)!!
        assertEquals(ReconciliationStatus.RESOLVED.name, closed.status)
        assertEquals("system:terminal-state", closed.resolvedBy)
        // Closed by the reversal, not by an operator's evidence.
        assertFalse(reconciliationService.isPaymentOutcomeSettled(orderId))
    }

    @Test
    fun `a settlement still reverses an authorization Payments recorded in the meantime`() = runBlocking {
        val (orderId, recordId) = parkedOnUnknownOutcome("late-settle")
        payment.noAuthorizationFor.remove(orderId)

        operations.settle(orderId, recordId, "settle-late-$orderId", ResolveReconciliationRequest("ref"), "ops-1")
        relayLatestOrderPlaced(orderId)

        // The settlement does not skip the reversal: the pipeline asks Payments again first.
        assertEquals(OrderStatus.FAILED, awaitTerminal(orderId).status)
        assertTrue(payment.reversedOrderIds.contains(orderId))
    }

    @Test
    fun `a settlement is refused on a resolved record or an order not waiting on it, and audited`() = runBlocking {
        val (orderId, recordId) = parkedOnUnknownOutcome("settle-audit")
        // An open PAYMENT_OUTCOME_UNKNOWN on an order that is not COMPENSATING.
        val notWaiting = expiredPaymentPending("settle-notwaiting")
        openIncident(notWaiting, ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN)
        val strayRecord = openRecords(notWaiting).single { it.kind == ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN.name }

        AuditLogCapture().use { audit ->
            operations.settle(orderId, recordId, "settle-a1-$orderId", ResolveReconciliationRequest("ref"), "ops-1")
            val again = assertFailsWith<ApiException> {
                operations.settle(orderId, recordId, "settle-a2-$orderId", ResolveReconciliationRequest("ref"), "ops-1")
            }
            assertEquals("RECONCILIATION_ALREADY_RESOLVED", again.code)
            val stray = assertFailsWith<ApiException> {
                operations.settle(notWaiting, strayRecord.id, "settle-a3-$notWaiting", ResolveReconciliationRequest("ref"), "ops-1")
            }
            assertEquals("ORDER_NOT_SETTLEABLE", stray.code)

            val lines = audit.forOrder(orderId).filter { it.action == "reconciliation.settle" }
            assertEquals(listOf("resolved", "rejected"), lines.map { it.outcome })
            assertEquals("ops-1", lines.first().fields["principalId"])
            assertEquals("RECONCILIATION_ALREADY_RESOLVED", lines.last().fields["reason"])
        }
    }

    @Test
    fun `a settlement needs the order's own unknown outcome, open, and evidence`() = runBlocking {
        val orderId = expiredPaymentPending("settle-refusals")
        payment.noAuthorizationFor.add(orderId)
        operations.abandon(orderId, "abandon-sr-$orderId", "ops-1")
        relayLatestOrderPlaced(orderId)
        val unknown = awaitOpenIncident(orderId, ReconciliationKind.PAYMENT_OUTCOME_UNKNOWN)
        val exhausted = openRecords(orderId).single { it.kind == ReconciliationKind.DELIVERY_EXHAUSTED.name }
        val other = expiredPaymentPending("settle-other")

        fun settle(order: UUID, record: UUID, evidence: String, key: String) = runBlocking {
            assertFailsWith<ApiException> {
                operations.settle(order, record, key, ResolveReconciliationRequest(evidence), "ops-1")
            }.code
        }
        assertEquals("EVIDENCE_REFERENCE_REQUIRED", settle(orderId, unknown.id, "  ", "settle-e-$orderId"))
        assertEquals("RECONCILIATION_KIND_NOT_SETTLEABLE", settle(orderId, exhausted.id, "ref", "settle-k-$orderId"))
        assertEquals("RECONCILIATION_RECORD_NOT_FOUND", settle(other, unknown.id, "ref", "settle-o-$orderId"))
        // None of those wrote anything: the order still waits.
        assertEquals(OrderStatus.COMPENSATING, orderRepository.findById(orderId)!!.status)

        val first = operations.settle(orderId, unknown.id, "settle-dup-$orderId", ResolveReconciliationRequest("ref"), "ops-1")
        val replay = operations.settle(orderId, unknown.id, "settle-dup-$orderId", ResolveReconciliationRequest("ref"), "ops-1")
        assertEquals(first, replay)
        assertEquals(3, outboxRows(orderId, "order-placed").size) // original, abandonment, one settlement
    }

    @Test
    fun `an abandonment whose reversal is refused stays non-terminal with a reversal incident`() = runBlocking {
        val orderId = expiredPaymentPending("abandon-refused")
        payment.failReversalFor.add(orderId)

        operations.abandon(orderId, "abandon-refused-$orderId", "ops-1")
        relayLatestOrderPlaced(orderId)

        assertEquals(OrderStatus.COMPENSATING, awaitStatus(orderId, OrderStatus.COMPENSATING).status)
        withTimeout(30_000) {
            while (openRecords(orderId).none { it.kind == ReconciliationKind.PAYMENT_REVERSAL_FAILURE.name }) delay(100)
        }
    }

    @Test
    fun `outside an abandonment a missing authorization still parks the compensation`() = runBlocking {
        // Ordinary compensation follows a definitive AUTHORIZED: Payments then finding none is a
        // cross-service inconsistency, never a reason to call the money settled.
        val response = orchestrator.checkout(request(), "it-noauth-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        inventory.refuseConfirmWith[response.orderId] = InventoryRefusalCode.RESERVATION_EXPIRED
        payment.noAuthorizationFor.add(response.orderId)

        relayOrderPlaced(response.orderId)

        assertEquals(OrderStatus.COMPENSATING, awaitStatus(response.orderId, OrderStatus.COMPENSATING).status)
        withTimeout(30_000) {
            while (openRecords(response.orderId).none { it.kind == ReconciliationKind.PAYMENT_REVERSAL_FAILURE.name }) delay(100)
        }
    }

    @Test
    fun `an abandonment is refused while the hold runs, outside PAYMENT_PENDING, and without an incident`() = runBlocking {
        val live = orchestrator.checkout(request(), "it-abandon-live-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(live.orderId, OrderStatus.PAYMENT_PENDING, "res-${live.orderId}")
        seedHoldExpiry(live.orderId, OffsetDateTime.now().plusMinutes(5))
        openIncident(live.orderId, ReconciliationKind.DELIVERY_EXHAUSTED)
        val confirming = orchestrator.checkout(request(), "it-abandon-conf-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(confirming.orderId, OrderStatus.CONFIRMING_INVENTORY, "res-${confirming.orderId}")
        seedHoldExpiry(confirming.orderId, OffsetDateTime.now().minusHours(2))
        openIncident(confirming.orderId, ReconciliationKind.DELIVERY_EXHAUSTED)
        val noIncident = orchestrator.checkout(request(), "it-abandon-none-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(noIncident.orderId, OrderStatus.PAYMENT_PENDING, "res-${noIncident.orderId}")
        seedHoldExpiry(noIncident.orderId, OffsetDateTime.now().minusHours(2))

        val cases = mapOf(
            live.orderId to "RESERVATION_HOLD_NOT_EXPIRED",
            confirming.orderId to "ORDER_NOT_ABANDONABLE",
            noIncident.orderId to "NO_OPEN_RECONCILIATION_RECORD",
        )
        cases.forEach { (orderId, code) ->
            val ex = assertFailsWith<ApiException> { operations.abandon(orderId, "abandon-no-$orderId", "ops-1") }
            assertEquals(code, ex.code)
            // Nothing moved and nothing was written.
            assertNotEquals(OrderStatus.COMPENSATING, orderRepository.findById(orderId)!!.status)
            assertEquals(1, outboxRows(orderId, "order-placed").size)
        }
    }

    @Test
    fun `repeating an abandonment key replays the response, and the abandonment is audited`() = runBlocking {
        val orderId = expiredPaymentPending("abandon-dup")
        payment.noAuthorizationFor.add(orderId)

        AuditLogCapture().use { audit ->
            val first = operations.abandon(orderId, "abandon-dup-$orderId", "ops-1")
            val replay = operations.abandon(orderId, "abandon-dup-$orderId", "ops-1")

            assertEquals(first, replay)
            assertEquals(2, outboxRows(orderId, "order-placed").size) // the original plus one abandonment
            val lines = audit.forOrder(orderId).filter { it.action == "reconciliation.abandon" }
            assertEquals(listOf("accepted", "replayed"), lines.map { it.outcome })
            assertEquals("ops-1", lines.first().fields["principalId"])
            assertEquals("user-1", lines.first().fields["ownerId"])
        }
    }

    @Test
    fun `a redrive without an open incident is refused`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-redrive4-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        seedState(response.orderId, OrderStatus.CONFIRMING_INVENTORY, "res-${response.orderId}")

        val ex = assertFailsWith<ApiException> {
            operations.redrive(response.orderId, "redrive-none-${response.orderId}", "ops-1")
        }

        assertEquals("NO_OPEN_RECONCILIATION_RECORD", ex.code)
        assertEquals(1, outboxRows(response.orderId, "order-placed").size)
    }

    @Test
    fun `resolving an invariant violation requires a terminal order, the right kind, and evidence`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-resolve-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        inventory.refuseConfirmWith[orderId] = InventoryRefusalCode.RESERVATION_ORDER_CONFLICT

        relayOrderPlaced(orderId)
        assertEquals(OrderStatus.FAILED, awaitTerminal(orderId).status)
        val record = openRecords(orderId).single()
        assertEquals(ReconciliationKind.INVARIANT_VIOLATION.name, record.kind)

        // Blank evidence: the closure has to be auditable.
        assertEquals(
            "EVIDENCE_REFERENCE_REQUIRED",
            assertFailsWith<ApiException> {
                operations.resolve(orderId, record.id, "r1-$orderId", ResolveReconciliationRequest("  "), "ops-1")
            }.code,
        )
        // A record belonging to another order reads as missing, never as forbidden.
        assertEquals(
            "RECONCILIATION_RECORD_NOT_FOUND",
            assertFailsWith<ApiException> {
                operations.resolve(orderId, UUID.randomUUID(), "r2-$orderId", ResolveReconciliationRequest("INC-1"), "ops-1")
            }.code,
        )

        val resolved = operations.resolve(orderId, record.id, "r3-$orderId", ResolveReconciliationRequest("INC-1"), "ops-1")

        assertEquals(ReconciliationStatus.RESOLVED.name, resolved.status)
        assertTrue(openRecords(orderId).isEmpty())
        // Resolving must not touch business state.
        assertEquals(OrderStatus.FAILED, orderRepository.findById(orderId)!!.status)

        // Already resolved: repeating with a different key must not reopen it.
        assertEquals(
            "RECONCILIATION_ALREADY_RESOLVED",
            assertFailsWith<ApiException> {
                operations.resolve(orderId, record.id, "r4-$orderId", ResolveReconciliationRequest("INC-2"), "ops-1")
            }.code,
        )
    }

    @Test
    fun `an operator cannot resolve a pending payment reversal`() = runBlocking {
        val response = orchestrator.checkout(request(), "it-resolve2-${UUID.randomUUID()}", "user-1", "user-1@example.com")
        val orderId = response.orderId
        inventory.refuseConfirmWith[orderId] = InventoryRefusalCode.RESERVATION_EXPIRED
        payment.failReversalFor.add(orderId)

        relayOrderPlaced(orderId)
        awaitStatus(orderId, OrderStatus.COMPENSATING)
        val record = openRecords(orderId).single()
        assertEquals(ReconciliationKind.PAYMENT_REVERSAL_FAILURE.name, record.kind)

        // Non-terminal order: unresolved money cannot be waived by closing the paperwork.
        assertEquals(
            "ORDER_NOT_TERMINAL",
            assertFailsWith<ApiException> {
                operations.resolve(orderId, record.id, "r5-$orderId", ResolveReconciliationRequest("INC-1"), "ops-1")
            }.code,
        )
        assertEquals(1, openRecords(orderId).size)
    }

    private fun request(
        token: String = "tok-123",
        travelDate: LocalDate = TRAVEL_DATE,
        seats: List<String> = listOf("1A", "1B"),
        // Two seats by default, so the line total is twice OFFER's per-seat price.
        totalAmount: BigDecimal = BigDecimal("200.00"),
        offerId: String = OFFER.offerId,
    ) = CheckoutRequest(
        routeId = "R1",
        seats = seats,
        travelDate = travelDate,
        totalAmount = totalAmount,
        paymentMethodToken = token,
        offerId = offerId,
    )

    // Stands in for Debezium: reads the committed order-placed outbox row(s) for this order and
    // publishes the JSON payload to Kafka, keyed by aggregate id (the order id).
    private suspend fun relayOrderPlaced(orderId: UUID) {
        outboxRepository.findAll().toList()
            .filter { it.aggregateId == orderId.toString() && it.type == "order-placed" }
            .forEach { relay(it) }
    }

    /** One outbox row onto its topic, the way the connector's `additional.placement` does it. */
    private fun relay(row: OutboxEvent) {
        val record = ProducerRecord<String, String>(row.type, row.aggregateId, row.payload.asString())
        row.traceparent?.let { record.headers().add("traceparent", it.toByteArray()) }
        relayTemplate.send(record)
    }

    /**
     * Relays only the newest `order-placed` row. Needed once a redrive adds a second one: replaying
     * both would drive the pipeline twice and make call-count assertions meaningless.
     */
    private suspend fun relayLatestOrderPlaced(orderId: UUID) {
        outboxRows(orderId, "order-placed")
            .maxByOrNull { it.createdAt }
            ?.let { relay(it) }
    }

    private suspend fun openIncident(orderId: UUID, kind: ReconciliationKind) {
        reconciliationRepository.upsertOpen(
            id = UUID.randomUUID(),
            orderId = orderId,
            kind = kind.name,
            orderState = orderRepository.findById(orderId)!!.status.name,
            correlationId = "corr-seed",
            failureClass = "dependency_error",
            failureDetail = "seeded by test",
        )
    }

    private suspend fun awaitAuditLines(audit: AuditLogCapture, orderId: UUID, count: Int): List<AuditLogCapture.Line> =
        withTimeout(10_000) {
            var lines = audit.forOrder(orderId)
            while (lines.size < count) {
                delay(100)
                lines = audit.forOrder(orderId)
            }
            lines
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

    /**
     * Sends a record to the dead-letter topic with the headers the recoverer actually attaches: the
     * container's wrapper as the exception, and what the listener threw as the cause
     * (`DeadLetterHeadersTest` pins that shape against the real recoverer).
     */
    private fun sendDeadLetter(
        orderId: UUID,
        causeFqcn: String,
        message: String,
        correlationId: String = "corr-dlt",
    ) {
        val event = OrderPlacedEvent(correlationId = correlationId, orderId = orderId, principalId = "user-1")
        val record = ProducerRecord<String, String>(
            "order-placed-dlt",
            orderId.toString(),
            objectMapper.writeValueAsString(event),
        )
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "order-placed".toByteArray())
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN, ListenerExecutionFailedException::class.java.name.toByteArray())
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, causeFqcn.toByteArray())
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, message.toByteArray())
        kafkaTemplate.send(record)
    }

    private fun dltCount(result: String): Double =
        meterRegistry.find("orders.events.dlt").tag("result", result).counter()?.count() ?: 0.0

    private fun exhaustedCount(topic: String, failureClass: String): Double =
        meterRegistry.find("orders.events.exhausted").tags("topic", topic, "failure_class", failureClass)
            .counter()?.count() ?: 0.0

    private suspend fun awaitDltCount(result: String, target: Double) = withTimeout(30_000) {
        while (dltCount(result) < target) delay(100)
    }

    /** Forces a durable phase so a resume path can be driven directly. */
    private suspend fun seedState(orderId: UUID, status: OrderStatus, reservationId: String?) {
        databaseClient.sql("UPDATE orders SET status = :s, reservation_id = :r WHERE id = :id")
            .bind("s", status.name)
            .let { if (reservationId == null) it.bindNull("r", String::class.java) else it.bind("r", reservationId) }
            .bind("id", orderId)
            .then()
            .awaitSingleOrNull()
    }

    private suspend fun seedHoldExpiry(orderId: UUID, expiresAt: OffsetDateTime) {
        databaseClient.sql("UPDATE orders SET reservation_expires_at = :e WHERE id = :id")
            .bind("e", expiresAt)
            .bind("id", orderId)
            .then()
            .awaitSingleOrNull()
    }

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

    /** A sequence advances across rollback, so the injected resource failure ends after six writes. */
    private suspend fun armHoldWriteFailures(orderId: UUID, failures: Int) {
        exec("CREATE SEQUENCE IF NOT EXISTS test_hold_write_seq")
        exec("SELECT setval('test_hold_write_seq', 1, false)")
        exec(
            """
            CREATE OR REPLACE FUNCTION test_fail_hold_write() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                IF NEW.status = 'PAYMENT_PENDING' AND NEW.id = '$orderId'::uuid
                   AND nextval('test_hold_write_seq') <= $failures THEN
                    RAISE EXCEPTION 'simulated database resource failure after Inventory hold'
                        USING ERRCODE = '08006';
                END IF;
                RETURN NEW;
            END; ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        exec("DROP TRIGGER IF EXISTS test_fail_hold_write ON orders")
        exec("CREATE TRIGGER test_fail_hold_write BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION test_fail_hold_write()")
    }

    /**
     * Fails the first [failures] attempts to write a reconciliation record for [orderId], standing in
     * for a database outage while the dead-letter consumer records the incident. Same sequence trick as
     * [armCommitCrash]: `nextval()` survives the aborted transaction, so the count advances.
     */
    private suspend fun armIncidentWriteFailures(orderId: UUID, failures: Int) {
        exec("CREATE SEQUENCE IF NOT EXISTS test_incident_write_seq")
        exec("SELECT setval('test_incident_write_seq', 1, false)")
        exec(
            """
            CREATE OR REPLACE FUNCTION test_fail_incident_write() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                -- Nested, not AND: PostgreSQL does not promise left-to-right evaluation, and only this
                -- order may consume the sequence.
                IF NEW.order_id = '$orderId'::uuid THEN
                    IF nextval('test_incident_write_seq') <= $failures THEN
                        RAISE EXCEPTION 'simulated database outage while recording an incident';
                    END IF;
                END IF;
                RETURN NEW;
            END; ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        exec("DROP TRIGGER IF EXISTS test_fail_incident_write ON reconciliation_records")
        exec(
            "CREATE TRIGGER test_fail_incident_write BEFORE INSERT ON reconciliation_records " +
                "FOR EACH ROW EXECUTE FUNCTION test_fail_incident_write()",
        )
    }

    private suspend fun disarmIncidentWriteFailures() =
        exec("DROP TRIGGER IF EXISTS test_fail_incident_write ON reconciliation_records")

    private fun deadLetterRecordFailed(): Double =
        meterRegistry.find("orders.events.dlt").tags("topic", "order-placed-dlt", "result", "record_failed")
            .counter()?.count() ?: 0.0

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
        val holdKeys = CopyOnWriteArrayList<Pair<UUID, String>>()
        val holdsByKey = ConcurrentHashMap<String, HoldOutcome.Held>()

        /** orderId -> the observation type the hold call would have been a child of. */
        val holdParentObservation = ConcurrentHashMap<UUID, String>()

        /** (routeId, travelDate, seat) triples held, i.e. what Inventory is keyed by. */
        val heldTriples = CopyOnWriteArrayList<Triple<String, LocalDate, String>>()
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
            holdKeys.add(request.orderId to idempotencyKey)
            parentObservationContextType()?.let { holdParentObservation[request.orderId] = it }
            return holdsByKey.computeIfAbsent(idempotencyKey) {
                request.seats.forEach { heldTriples.add(Triple(request.routeId, request.travelDate, it)) }
                HoldOutcome.Held("res-${request.orderId}", OffsetDateTime.now().plusMinutes(10))
            }
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

        // The checkout pipeline never calls this: compensation only ever releases a HELD hold
        // (releaseSeats above). The post-sale refund's own use of it is exercised by
        // RefundPipelineIntegrationTest's own doubles, not this file's. Implemented only to satisfy
        // InventoryClient, and kept trivial on purpose so an unused knob does not accumulate here.
        override suspend fun refundReservation(reservationId: String, orderId: UUID): RefundReservationOutcome =
            RefundReservationOutcome.Refunded(reservationId)
    }

    /**
     * Catalog stand-in seeded with one offer. Not a mock of a money-path collaborator like the other
     * two — it is what makes the amount trustworthy — so it answers the real shape and lets a test
     * take the offer away ([knownOffers]) or break the service ([failWith]) on purpose.
     */
    class ControllableCatalogClient : CatalogClient {
        val knownOffers = ConcurrentHashMap<String, ResolvedOffer>().apply { put(OFFER.offerId, OFFER) }
        val lookedUp = CopyOnWriteArrayList<String>()

        @Volatile
        var failWith: Throwable? = null

        override suspend fun getOffer(offerId: String): OfferOutcome {
            lookedUp.add(offerId)
            failWith?.let { throw it }
            return knownOffers[offerId]?.let { OfferOutcome.Found(it) } ?: OfferOutcome.NotFound
        }

        // Derived from the seeded offers, so a route cannot disagree with its own offer about when it
        // departs. Only the seat-map façade calls this; the checkout pipeline never does.
        override suspend fun getRoute(routeId: String): RouteOutcome =
            knownOffers.values.firstOrNull { it.routeId == routeId }
                ?.let { RouteOutcome.Found(RouteSchedule(it.routeId, it.departureTime, it.departureZone)) }
                ?: RouteOutcome.NotFound
    }

    class ControllablePaymentClient : PaymentClient {
        val authorizedOrderIds = CopyOnWriteArrayList<UUID>()
        val authorizeKeys = CopyOnWriteArrayList<Pair<UUID, String>>()
        /** The full requests, so what actually reaches Payments — amount and currency — is assertable. */
        val authorizeRequests = CopyOnWriteArrayList<PaymentRequest>()
        val refundedOrderIds = CopyOnWriteArrayList<UUID>()
        /** Only the reversals Payments answered as done, unlike [refundedOrderIds], every one asked for. */
        val reversedOrderIds = CopyOnWriteArrayList<UUID>()
        /** The idempotency keys `refund` was called with, so a test can tell compensation from a
         * commercial refund apart (`refund-{orderId}` vs `pay-refund-{orderId}`). */
        val refundIdempotencyKeys = CopyOnWriteArrayList<String>()

        /** orderId -> answer the reversal with a definitive failure instead of success. */
        val failReversalFor = ConcurrentHashMap.newKeySet<UUID>()

        /** orderId -> Payments holds no authorization for it: the reversal answers NOT_FOUND. */
        val noAuthorizationFor = ConcurrentHashMap.newKeySet<UUID>()

        /** orderId -> Payments holds an authorization that is not AUTHORIZED (e.g. declined). */
        val unsettledAuthorizationFor = ConcurrentHashMap.newKeySet<UUID>()

        /** orderId -> how many authorizations to refuse as an open Payments breaker would, before answering. */
        val unavailableFor = ConcurrentHashMap<UUID, AtomicInteger>()

        override suspend fun authorizePayment(
            request: PaymentRequest,
            idempotencyKey: String,
            correlationId: String,
        ): AuthorizationOutcome {
            authorizeKeys.add(request.orderId to idempotencyKey)
            if (request.paymentMethodToken == DECLINE_TOKEN) {
                return AuthorizationOutcome.Declined("DECLINED")
            }
            if ((unavailableFor[request.orderId]?.getAndDecrement() ?: 0) > 0) {
                throw CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("payments"))
            }
            authorizedOrderIds.add(request.orderId)
            authorizeRequests.add(request)
            return AuthorizationOutcome.Authorized("test-ref")
        }

        override suspend fun refund(orderId: UUID, idempotencyKey: String): ReversalOutcome {
            refundedOrderIds.add(orderId)
            refundIdempotencyKeys.add(idempotencyKey)
            if (orderId in noAuthorizationFor) return ReversalOutcome.Failed("NOT_FOUND", "AUTHORIZATION_NOT_FOUND")
            if (orderId in unsettledAuthorizationFor) return ReversalOutcome.Failed("FAILED", "INVALID_AUTHORIZATION_STATUS")
            return if (orderId in failReversalFor) {
                ReversalOutcome.Failed("FAILED", "forced by test")
            } else {
                reversedOrderIds.add(orderId)
                ReversalOutcome.Reversed(providerReference = "test-refund-ref")
            }
        }
    }

    @TestConfiguration
    class TestDoubles {
        @Bean @Primary fun inventoryDouble() = ControllableInventoryClient()

        @Bean @Primary fun paymentDouble() = ControllablePaymentClient()

        @Bean @Primary fun catalogDouble() = ControllableCatalogClient()

        // No real Keycloak in the test: back off the network-fetching decoder. It is never invoked
        // because these tests drive the orchestrator directly rather than over HTTP.
        @Bean @Primary fun testJwtDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { Mono.empty() }
    }
}
