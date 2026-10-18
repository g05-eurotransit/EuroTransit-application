package it.polito.cpo.service

import io.micrometer.observation.ObservationRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.polito.cpo.client.AuthorizationOutcome
import it.polito.cpo.client.PaymentWindowOutcome
import it.polito.cpo.client.ConfirmReservationOutcome
import it.polito.cpo.client.HoldOutcome
import it.polito.cpo.client.InventoryClient
import it.polito.cpo.client.InventoryRefusalCode
import it.polito.cpo.client.PaymentClient
import it.polito.cpo.client.RefundReservationOutcome
import it.polito.cpo.client.ReversalOutcome
import it.polito.cpo.contracts.events.RefundRequestedEvent
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.controller.dtos.OrderView
import it.polito.cpo.model.IdempotentRequest
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationRecord
import it.polito.cpo.observability.ApiException
import it.polito.cpo.repository.IdempotentRequestRepository
import it.polito.cpo.repository.OrderRepository
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReconciliationRecordRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the post-sale refund (ADR-015), with lightweight in-memory fakes — no database, no
 * broker — mirroring how `CheckoutOrchestratorTest` tests the checkout pipeline.
 *
 * [FakeOrderService.markRefunding] replicates `OrderRepository.markRefunding`'s compare-and-set
 * predicate exactly, cutoff arithmetic included, for the same reason `CheckoutOrchestratorTest`'s
 * fake does: the authoritative proof of the SQL itself is `OrderTransitionsIntegrationTest`, this
 * fake only has to agree with it. The transactional boundary is faked the same way too — a
 * [TransactionalOperator] that runs the block inline — so these tests prove control flow, not
 * rollback; rollback is covered by the future Kafka-backed integration test.
 */
class RefundOrchestratorTest {

    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val inlineTx: TransactionalOperator = TransactionalOperator.create(NoopTxManager)

    private object NoopTx : ReactiveTransaction {
        override fun isNewTransaction() = true
        override fun setRollbackOnly() {}
        override fun isRollbackOnly() = false
        override fun isCompleted() = false
    }

    private object NoopTxManager : ReactiveTransactionManager {
        override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> =
            Mono.just(NoopTx)
        override fun commit(transaction: ReactiveTransaction): Mono<Void> = Mono.empty()
        override fun rollback(transaction: ReactiveTransaction): Mono<Void> = Mono.empty()
    }

    // --- fakes ------------------------------------------------------------------------------------

    private class FakeOrderService : OrderService(
        mock(OrderRepository::class.java),
        mock(IdempotentRequestRepository::class.java),
    ) {
        val ordersById = HashMap<UUID, Order>()
        val idempotencyStore = HashMap<String, IdempotentRequest>()

        override suspend fun getOrderById(id: UUID): Order? = ordersById[id]

        override suspend fun findIdempotentRequest(key: String): IdempotentRequest? = idempotencyStore[key]

        override suspend fun saveIdempotentRequest(
            key: String,
            responseBody: String,
            principalId: String,
            operation: String,
            requestFingerprint: String,
        ): IdempotentRequest {
            val request = IdempotentRequest(key, responseBody, LocalDateTime.now(), principalId, operation, requestFingerprint)
            idempotencyStore[key] = request
            return request
        }

        /** Mirrors OrderRepository.markRefunding's WHERE clause, cutoff arithmetic included. */
        override suspend fun markRefunding(id: UUID): Long {
            val o = ordersById[id] ?: return 0
            if (o.status != OrderStatus.CONFIRMED) return 0
            if (o.offerId == null) return 0
            if (o.refundable != true) return 0
            val departure = o.departureAt ?: return 0
            val cutoff = (o.refundCutoffMinutesBeforeDeparture ?: 0).toLong()
            if (!OffsetDateTime.now().isBefore(departure.minusMinutes(cutoff))) return 0
            o.status = OrderStatus.REFUNDING
            o.refundRequestedAt = OffsetDateTime.now()
            return 1
        }

        override suspend fun markRefunded(id: UUID, providerReference: String?): Long {
            val o = ordersById[id] ?: return 0
            if (o.status != OrderStatus.REFUNDING) return 0
            o.status = OrderStatus.REFUNDED
            o.refundedAt = OffsetDateTime.now()
            o.refundProviderReference = providerReference
            return 1
        }
    }

    private class FakeReconciliationService : ReconciliationService(
        mock(ReconciliationRecordRepository::class.java),
        SimpleMeterRegistry(),
        TransactionalOperator.create(NoopTxManager),
    ) {
        val opened = CopyOnWriteArrayList<Pair<ReconciliationKind, String>>()
        val closedFor = CopyOnWriteArrayList<UUID>()

        override suspend fun recordPaymentReversalFailure(
            orderId: UUID, orderState: String, correlationId: String, failureClass: String, detail: String?,
        ): ReconciliationRecord {
            opened.add(ReconciliationKind.PAYMENT_REVERSAL_FAILURE to failureClass)
            return record(orderId, ReconciliationKind.PAYMENT_REVERSAL_FAILURE, orderState, correlationId, failureClass)
        }

        override suspend fun recordSeatReturnFailure(
            orderId: UUID, orderState: String, correlationId: String, failureClass: String, detail: String?,
        ): ReconciliationRecord {
            opened.add(ReconciliationKind.SEAT_RETURN_FAILURE to failureClass)
            return record(orderId, ReconciliationKind.SEAT_RETURN_FAILURE, orderState, correlationId, failureClass)
        }

        override suspend fun closeAutoResolvable(orderId: UUID): Long {
            closedFor.add(orderId)
            return 0
        }

        override fun countRequired(record: ReconciliationRecord) = Unit

        fun kindsOpened() = opened.map { it.first }

        private fun record(
            orderId: UUID, kind: ReconciliationKind, orderState: String, correlationId: String, failureClass: String,
        ) = ReconciliationRecord(orderId = orderId, kind = kind.name, orderState = orderState, correlationId = correlationId, failureClass = failureClass)
    }

    private class StubInventoryClient(
        private val refund: () -> RefundReservationOutcome = { RefundReservationOutcome.Refunded("res-1") },
    ) : InventoryClient {
        val refundCalls = CopyOnWriteArrayList<Pair<String, UUID>>()

        override suspend fun reserveSeats(request: ReservationRequest, idempotencyKey: String, userId: String): HoldOutcome =
            throw UnsupportedOperationException("not used by the refund pipeline")

        override suspend fun confirmReservation(reservationId: String, orderId: UUID): ConfirmReservationOutcome =
            throw UnsupportedOperationException("not used by the refund pipeline")

        // The refund never opens a payment window (ADR-016); implemented only to satisfy InventoryClient.

        override suspend fun startPaymentWindow(reservationId: String, orderId: UUID): PaymentWindowOutcome =

            PaymentWindowOutcome.Opened(reservationId, OffsetDateTime.now().plusMinutes(10))


        override suspend fun releaseSeats(reservationId: String, idempotencyKey: String) =
            throw UnsupportedOperationException("not used by the refund pipeline")

        override suspend fun refundReservation(reservationId: String, orderId: UUID): RefundReservationOutcome {
            refundCalls.add(reservationId to orderId)
            return refund()
        }
    }

    private class StubPaymentClient(
        private val reversal: () -> ReversalOutcome = { ReversalOutcome.Reversed(providerReference = "prov-ref") },
    ) : PaymentClient {
        val refundCalls = CopyOnWriteArrayList<UUID>()
        val refundKeys = CopyOnWriteArrayList<String>()

        override suspend fun authorizePayment(request: PaymentRequest, idempotencyKey: String, correlationId: String): AuthorizationOutcome =
            throw UnsupportedOperationException("not used by the refund pipeline")

        override suspend fun refund(orderId: UUID, idempotencyKey: String): ReversalOutcome {
            refundCalls.add(orderId)
            refundKeys.add(idempotencyKey)
            return reversal()
        }
    }

    // --- harness ------------------------------------------------------------------------------------

    private fun orchestratorFor(
        service: FakeOrderService,
        inventory: StubInventoryClient = StubInventoryClient(),
        payment: StubPaymentClient = StubPaymentClient(),
        reconciliation: FakeReconciliationService = FakeReconciliationService(),
    ) = RefundOrchestrator(
        orderService = service,
        inventoryClient = inventory,
        paymentClient = payment,
        outboxEventRepository = mock(OutboxEventRepository::class.java),
        reconciliationService = reconciliation,
        objectMapper = mapper,
        meterRegistry = SimpleMeterRegistry(),
        txOperator = inlineTx,
        // NOOP: no tracing in these tests, so rows carry no traceparent (OrderOutboxTest covers it).
        orderOutbox = OrderOutbox(mapper, ObservationRegistry.NOOP),
    )

    private fun refundableOrder(
        id: UUID,
        userId: String = "user-1",
        status: OrderStatus = OrderStatus.CONFIRMED,
        offerId: String? = "of-002",
        refundable: Boolean? = true,
        refundCutoffMinutesBeforeDeparture: Int? = 60,
        departureAt: OffsetDateTime? = OffsetDateTime.now().plusDays(1),
        reservationId: String? = "res-1",
    ) = Order(
        id = id,
        userId = userId,
        status = status,
        routeId = "R1",
        seats = "1A",
        travelDate = LocalDate.now().plusDays(1),
        totalAmount = BigDecimal("49.90"),
        currency = "EUR",
        createdAt = LocalDateTime.now(),
        reservationId = reservationId,
        offerId = offerId,
        refundable = refundable,
        refundCutoffMinutesBeforeDeparture = refundCutoffMinutesBeforeDeparture,
        offerTermsVersion = 1,
        departureAt = departureAt,
    ).apply { setAsNew(false) }

    private fun event(orderId: UUID, userId: String = "user-1") =
        RefundRequestedEvent(correlationId = "corr-1", orderId = orderId, principalId = userId)

    // --- synchronous entry: eligibility and idempotency ----------------------------------------------

    @Test
    fun `a confirmed refundable order inside its cutoff is accepted into REFUNDING`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply { ordersById[orderId] = refundableOrder(orderId) }
        val orchestrator = orchestratorFor(service)

        val view = orchestrator.refund(orderId, "idem-1", "user-1", isStaff = false)

        assertEquals(OrderStatus.REFUNDING, view.status)
        assertEquals(OrderStatus.REFUNDING, service.ordersById[orderId]!!.status)
    }

    @Test
    fun `a non-owner gets 404, never 403`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply { ordersById[orderId] = refundableOrder(orderId, userId = "user-1") }
        val orchestrator = orchestratorFor(service)

        val ex = assertThrows<ApiException> { orchestrator.refund(orderId, "idem-1", "user-2", isStaff = false) }
        assertEquals(HttpStatus.NOT_FOUND, ex.status)
        assertEquals("ORDER_NOT_FOUND", ex.code)
    }

    @Test
    fun `operations staff may refund an order they do not own`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply { ordersById[orderId] = refundableOrder(orderId, userId = "user-1") }
        val orchestrator = orchestratorFor(service)

        val view = orchestrator.refund(orderId, "idem-1", "ops-1", isStaff = true)
        assertEquals(OrderStatus.REFUNDING, view.status)
    }

    @Test
    fun `an order that is not CONFIRMED is refused with its own code`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.FAILED)
        }
        val orchestrator = orchestratorFor(service)

        val ex = assertThrows<ApiException> { orchestrator.refund(orderId, "idem-1", "user-1", isStaff = false) }
        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("ORDER_NOT_CONFIRMED", ex.code)
    }

    @Test
    fun `an order with no offer is refused with its own code`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, offerId = null, refundable = null, refundCutoffMinutesBeforeDeparture = null, departureAt = null)
        }
        val orchestrator = orchestratorFor(service)

        val ex = assertThrows<ApiException> { orchestrator.refund(orderId, "idem-1", "user-1", isStaff = false) }
        assertEquals("ORDER_HAS_NO_OFFER", ex.code)
    }

    @Test
    fun `a non-refundable offer is refused with its own code`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, refundable = false, refundCutoffMinutesBeforeDeparture = null)
        }
        val orchestrator = orchestratorFor(service)

        val ex = assertThrows<ApiException> { orchestrator.refund(orderId, "idem-1", "user-1", isStaff = false) }
        assertEquals("OFFER_NOT_REFUNDABLE", ex.code)
    }

    @Test
    fun `a request past the cutoff is refused with its own code`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, departureAt = OffsetDateTime.now().minusMinutes(30))
        }
        val orchestrator = orchestratorFor(service)

        val ex = assertThrows<ApiException> { orchestrator.refund(orderId, "idem-1", "user-1", isStaff = false) }
        assertEquals("REFUND_CUTOFF_PASSED", ex.code)
    }

    @Test
    fun `a key accepted before the cutoff still replays its original result after the cutoff has since passed`() = runTest {
        // Two opposite behaviours on the same time condition (ADR-015): a replay of an already-
        // accepted key must win over a cutoff that has since passed, while a brand new request in the
        // same state must not. The earlier version of this test drove that through two real refund()
        // calls, but the first call always moves the order to REFUNDING -- so the "replay" it proved
        // was a replay for an order no longer CONFIRMED, which would have been refused with
        // ORDER_NOT_CONFIRMED regardless of the cutoff, not REFUND_CUTOFF_PASSED. It never actually
        // exercised the cutoff arm of the replay rule.
        //
        // This version isolates it: the order is left CONFIRMED with its cutoff already passed --
        // a *fresh* request against this exact row would be refused with REFUND_CUTOFF_PASSED, and
        // only REFUND_CUTOFF_PASSED, since every other precondition holds -- while the idempotency
        // store is seeded directly with the response an earlier, in-time acceptance would have stored
        // (mirroring exactly what refund() itself writes: the fingerprint over orderId, and an
        // OrderView with the order in REFUNDING). The replay must win by running before the cutoff
        // check, not after.
        val orderId = UUID.randomUUID()
        val order = refundableOrder(orderId, departureAt = OffsetDateTime.now().minusMinutes(30))
        val service = FakeOrderService().apply { ordersById[orderId] = order }
        val orchestrator = orchestratorFor(service)

        val key = "idem-precutoff"
        val storedView = OrderView(
            orderId = orderId,
            userId = order.userId,
            status = OrderStatus.REFUNDING,
            routeId = order.routeId,
            seats = order.seats.split(","),
            travelDate = order.travelDate,
            totalAmount = order.totalAmount,
            currency = order.currency,
            createdAt = order.createdAt,
            offerId = order.offerId,
            refundable = order.refundable,
            refundCutoffMinutesBeforeDeparture = order.refundCutoffMinutesBeforeDeparture,
            departureAt = order.departureAt,
        )
        service.idempotencyStore[key] = IdempotentRequest(
            key, mapper.writeValueAsString(storedView), LocalDateTime.now(), "user-1", "refund", refundFingerprint(orderId),
        )

        // Sanity check on the premise: a *different* key against this same, already-past-cutoff row
        // is refused on the cutoff specifically, proving the row really is in the state the replay
        // has to overcome.
        val fresh = assertThrows<ApiException> { orchestrator.refund(orderId, "idem-fresh", "user-1", isStaff = false) }
        assertEquals("REFUND_CUTOFF_PASSED", fresh.code)

        val replay = orchestrator.refund(orderId, key, "user-1", isStaff = false)

        assertEquals(orderId, replay.orderId)
        assertEquals(OrderStatus.REFUNDING, replay.status)
        // And the row itself was never touched by the replay: no eligibility check, no transition
        // attempted -- it is CONFIRMED exactly as this test seeded it.
        assertEquals(OrderStatus.CONFIRMED, service.ordersById[orderId]!!.status)
    }

    /** Mirrors `RefundOrchestrator`'s private fingerprint: just the operation and the order id. */
    private fun refundFingerprint(orderId: UUID): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("refund|$orderId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `a different key for the same order after acceptance is refused, not replayed`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply { ordersById[orderId] = refundableOrder(orderId) }
        val orchestrator = orchestratorFor(service)

        orchestrator.refund(orderId, "idem-1", "user-1", isStaff = false)

        val ex = assertThrows<ApiException> { orchestrator.refund(orderId, "idem-2", "user-1", isStaff = false) }
        assertEquals("ORDER_NOT_CONFIRMED", ex.code) // already moved to REFUNDING by the first call
    }

    @Test
    fun `the same key reused for a different order is an idempotency conflict`() = runTest {
        val orderA = UUID.randomUUID()
        val orderB = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderA] = refundableOrder(orderA)
            ordersById[orderB] = refundableOrder(orderB)
        }
        val orchestrator = orchestratorFor(service)

        orchestrator.refund(orderA, "idem-shared", "user-1", isStaff = false)

        val ex = assertThrows<ApiException> { orchestrator.refund(orderB, "idem-shared", "user-1", isStaff = false) }
        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code)
    }

    // --- asynchronous consumer: the reversal, in ADR-015's order --------------------------------------

    @Test
    fun `a refund is reversed end to end -- money first, seat second, then REFUNDED`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDING)
        }
        val payment = StubPaymentClient()
        val inventory = StubInventoryClient()
        val orchestrator = orchestratorFor(service, inventory, payment)

        orchestrator.processRefundRequested(event(orderId))

        assertEquals(listOf(orderId), payment.refundCalls)
        assertEquals(listOf("pay-refund-$orderId"), payment.refundKeys)
        assertEquals(listOf("res-1" to orderId), inventory.refundCalls)
        val order = service.ordersById[orderId]!!
        assertEquals(OrderStatus.REFUNDED, order.status)
        assertEquals("prov-ref", order.refundProviderReference)
    }

    @Test
    fun `never refund-{orderId} -- the compensation key is not reused for a commercial refund`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDING)
        }
        val payment = StubPaymentClient()
        val orchestrator = orchestratorFor(service, payment = payment)

        orchestrator.processRefundRequested(event(orderId))

        assertTrue(payment.refundKeys.none { it == "refund-$orderId" })
    }

    @Test
    fun `a definitive reversal failure leaves REFUNDING open and never attempts the seat return`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDING)
        }
        val payment = StubPaymentClient(reversal = { ReversalOutcome.Failed("FAILED", "provider refused") })
        val inventory = StubInventoryClient()
        val reconciliation = FakeReconciliationService()
        val orchestrator = orchestratorFor(service, inventory, payment, reconciliation)

        orchestrator.processRefundRequested(event(orderId))

        assertEquals(OrderStatus.REFUNDING, service.ordersById[orderId]!!.status)
        assertTrue(inventory.refundCalls.isEmpty()) // money first: seat return never attempted
        assertEquals(listOf(ReconciliationKind.PAYMENT_REVERSAL_FAILURE), reconciliation.kindsOpened())
    }

    @Test
    fun `a definitive seat-return failure leaves REFUNDING open with the money already reversed`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDING)
        }
        val payment = StubPaymentClient()
        val inventory = StubInventoryClient(
            refund = { RefundReservationOutcome.Refused(InventoryRefusalCode.RESERVATION_SEAT_CONFLICT, "forced by test") },
        )
        val reconciliation = FakeReconciliationService()
        val orchestrator = orchestratorFor(service, inventory, payment, reconciliation)

        orchestrator.processRefundRequested(event(orderId))

        // The customer already has their money (the reversal was attempted and succeeded)...
        assertEquals(listOf(orderId), payment.refundCalls)
        // ...but the order is NOT terminal: there is no REFUND_FAILED (ADR-015).
        assertEquals(OrderStatus.REFUNDING, service.ordersById[orderId]!!.status)
        assertNull(service.ordersById[orderId]!!.refundedAt)
        assertEquals(listOf(ReconciliationKind.SEAT_RETURN_FAILURE), reconciliation.kindsOpened())
    }

    @Test
    fun `a redelivery for an order already REFUNDED is acknowledged without any remote call`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDED)
        }
        val payment = StubPaymentClient()
        val inventory = StubInventoryClient()
        val orchestrator = orchestratorFor(service, inventory, payment)

        orchestrator.processRefundRequested(event(orderId))

        assertTrue(payment.refundCalls.isEmpty())
        assertTrue(inventory.refundCalls.isEmpty())
    }

    @Test
    fun `a redelivery of refund-requested after REFUNDED makes no further remote calls`() = runTest {
        // A Kafka redelivery of the SAME event, processed twice: the first pass reverses the money,
        // returns the seat and commits REFUNDED; a second delivery of that same message must not
        // repeat either remote call. (A redelivery that instead arrives mid-flight -- after the
        // reversal committed but before the seat return did -- is exactly the REFUNDING case covered
        // by the happy-path test above: both calls are safe to repeat because each is idempotent on
        // the far side, not because this orchestrator tracks which one already ran.)
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDING)
        }
        val payment = StubPaymentClient()
        val inventory = StubInventoryClient()
        val orchestrator = orchestratorFor(service, inventory, payment)
        val sameEvent = event(orderId)

        orchestrator.processRefundRequested(sameEvent)
        assertEquals(OrderStatus.REFUNDED, service.ordersById[orderId]!!.status)

        orchestrator.processRefundRequested(sameEvent)

        assertEquals(1, payment.refundCalls.size)
        assertEquals(1, inventory.refundCalls.size)
    }

    // --- money-path audit (DoD §5.9) --------------------------------------------------------------

    @Test
    fun `an accepted refund is audited under the caller`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply { ordersById[orderId] = refundableOrder(orderId) }

        AuditLogCapture().use { audit ->
            orchestratorFor(service).refund(orderId, "idem-audit", "user-1", isStaff = false)

            val line = audit.lines.single()
            assertEquals("refund", line.action)
            assertEquals("accepted", line.outcome)
            assertEquals("user-1", line.fields["principalId"])
            assertEquals(orderId.toString(), line.fields["orderId"])
            assertFalse("ownerId" in line.fields)
        }
    }

    @Test
    fun `a staff refund is audited under the staff member, naming the owner`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply { ordersById[orderId] = refundableOrder(orderId, userId = "user-1") }

        AuditLogCapture().use { audit ->
            orchestratorFor(service).refund(orderId, "idem-audit", "ops-1", isStaff = true)

            val line = audit.lines.single()
            assertEquals("ops-1", line.fields["principalId"])
            assertEquals("user-1", line.fields["ownerId"])
        }
    }

    @Test
    fun `a refused refund is audited with its code`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.FAILED)
        }

        AuditLogCapture().use { audit ->
            assertThrows<ApiException> { orchestratorFor(service).refund(orderId, "idem-audit", "user-1", isStaff = false) }

            val line = audit.lines.single()
            assertEquals("rejected", line.outcome)
            assertEquals("ORDER_NOT_CONFIRMED", line.fields["reason"])
        }
    }

    @Test
    fun `a completed refund is audited by the pipeline under the owner and the event's correlation id`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDING)
        }

        AuditLogCapture().use { audit ->
            orchestratorFor(service).processRefundRequested(event(orderId))

            val line = audit.lines.single()
            assertEquals("refund.pipeline", line.action)
            assertEquals("refunded", line.outcome)
            assertEquals("user-1", line.fields["principalId"])
            assertEquals("corr-1", line.correlationId)
        }
    }

    @Test
    fun `a refund that cannot reverse the money is audited as failed`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDING)
        }
        val payment = StubPaymentClient(reversal = { ReversalOutcome.Failed("FAILED", "provider refused") })

        AuditLogCapture().use { audit ->
            orchestratorFor(service, payment = payment).processRefundRequested(event(orderId))

            val line = audit.lines.single()
            assertEquals("failed", line.outcome)
            assertEquals(FailureClassifier.PAYMENT_REVERSAL_FAILED, line.fields["reason"])
        }
    }

    @Test
    fun `a refund whose seat cannot be returned is audited as failed`() = runTest {
        val orderId = UUID.randomUUID()
        val service = FakeOrderService().apply {
            ordersById[orderId] = refundableOrder(orderId, status = OrderStatus.REFUNDING)
        }
        val inventory = StubInventoryClient(
            refund = { RefundReservationOutcome.Refused(InventoryRefusalCode.RESERVATION_SEAT_CONFLICT, "forced by test") },
        )

        AuditLogCapture().use { audit ->
            orchestratorFor(service, inventory).processRefundRequested(event(orderId))

            val line = audit.lines.single()
            assertEquals("failed", line.outcome)
            assertEquals("reservation_seat_conflict", line.fields["reason"])
        }
    }
}
