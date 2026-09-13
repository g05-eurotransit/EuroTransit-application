package it.polito.cpo.service

import it.polito.cpo.client.AuthorizationOutcome
import it.polito.cpo.client.CatalogClient
import it.polito.cpo.client.ConfirmReservationOutcome
import it.polito.cpo.client.HoldOutcome
import it.polito.cpo.client.InventoryClient
import it.polito.cpo.client.InventoryRefusalCode
import it.polito.cpo.client.OfferOutcome
import it.polito.cpo.client.PaymentClient
import it.polito.cpo.client.ResolvedOffer
import it.polito.cpo.client.ReversalOutcome
import it.polito.cpo.contracts.catalog.RefundPolicy
import it.polito.cpo.contracts.payments.PaymentRequest
import it.polito.cpo.contracts.inventory.ReservationRequest
import it.polito.cpo.controller.dtos.CheckoutRequest
import it.polito.cpo.controller.dtos.CheckoutResponse
import it.polito.cpo.contracts.events.OrderPlacedEvent
import it.polito.cpo.model.IdempotentRequest
import it.polito.cpo.model.Order
import it.polito.cpo.model.OrderStatus
import it.polito.cpo.model.ReconciliationKind
import it.polito.cpo.model.ReconciliationRecord
import it.polito.cpo.repository.IdempotentRequestRepository
import it.polito.cpo.repository.OrderRepository
import it.polito.cpo.repository.OutboxEventRepository
import it.polito.cpo.repository.ReconciliationRecordRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.polito.cpo.observability.ApiException
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
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the checkout orchestration logic, with lightweight in-memory fakes (no database, no
 * broker). Focus: idempotency, the durable state machine, the resume-per-phase rule, and above all the
 * unknown-versus-definitive outcome truth table.
 *
 * [FakeOrderService] replicates the repository's compare-and-set predicates **exactly**. That matters:
 * an earlier version only checked `status != CANCELLED`, which made every one of these tests pass
 * regardless of whether the transitions were correct. The authoritative proof of the SQL itself is
 * `OrderTransitionsIntegrationTest`; this fake only has to agree with it.
 *
 * The transactional boundary is faked by a [TransactionalOperator] that runs the block inline, so these
 * tests prove control flow, not rollback. Rollback is covered in `CheckoutPipelineIntegrationTest`.
 */
class CheckoutOrchestratorTest {

    /** A stable future date: the checkout guard rejects anything in the past. */
    private val TRAVEL_DATE: LocalDate = LocalDate.now().plusDays(7)

    private companion object {
        /**
         * Priced to match the 100.00 every request in this file asks for, so the offer is only
         * "wrong" in the tests that deliberately make it so.
         */
        val OFFER = ResolvedOffer(
            offerId = "of-001",
            routeId = "R1",
            price = BigDecimal("100.00"),
            currency = "EUR",
            refundPolicy = RefundPolicy(refundable = true, cutoffMinutesBeforeDeparture = 60),
            termsVersion = 3,
            departureTime = LocalTime.of(7, 0),
            departureZone = ZoneId.of("Europe/Rome"),
        )
    }

    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    // Real TransactionalOperator over a no-op reactive transaction manager: it runs the block and
    // "commits" without a database — enough for unit tests, which only need the block's effects and
    // return value, not real commit/rollback semantics.
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
        mock(IdempotentRequestRepository::class.java)
    ) {
        /** Statuses persisted, in order. Thread-safe: the async pipeline appends from another thread. */
        val savedOrderStatuses = CopyOnWriteArrayList<OrderStatus>()
        val idempotencyStore = HashMap<String, IdempotentRequest>()
        val ordersById = HashMap<UUID, Order>()
        var seeded: IdempotentRequest? = null

        /** Simulates a crash/rollback of the terminal commit, once. */
        var failNextMarkConfirmed = false

        override suspend fun findIdempotentRequest(key: String): IdempotentRequest? =
            seeded ?: idempotencyStore[key]

        override suspend fun getOrderById(id: UUID): Order? = ordersById[id]

        override suspend fun saveOrder(order: Order): Order {
            savedOrderStatuses.add(order.status)
            ordersById[order.getId()] = order
            return order
        }

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

        /** Mirrors a `UPDATE ... WHERE id = :id AND status IN (:from)` conditional update. */
        private fun cas(id: UUID, from: Set<OrderStatus>, to: OrderStatus, clearToken: Boolean = false): Long {
            val o = ordersById[id] ?: return 0
            if (o.status !in from) return 0
            o.status = to
            if (clearToken) o.paymentMethodToken = null
            savedOrderStatuses.add(to)
            return 1
        }

        override suspend fun markReserving(id: UUID): Long =
            cas(id, setOf(OrderStatus.ACCEPTED, OrderStatus.RESERVING), OrderStatus.RESERVING)

        /** What the pipeline stored alongside the reservation id, so a test can assert the pair. */
        @Volatile
        var storedExpiresAt: OffsetDateTime? = null

        override suspend fun markPaymentPending(
            id: UUID,
            reservationId: String,
            reservationExpiresAt: OffsetDateTime,
        ): Long {
            val o = ordersById[id] ?: return 0
            // The self-transition is pinned to the same reservation id, exactly as the SQL is.
            val accepted = o.status == OrderStatus.RESERVING ||
                (o.status == OrderStatus.PAYMENT_PENDING && o.reservationId == reservationId)
            if (!accepted) return 0
            o.status = OrderStatus.PAYMENT_PENDING
            o.reservationId = reservationId
            o.reservationExpiresAt = reservationExpiresAt
            storedExpiresAt = reservationExpiresAt
            savedOrderStatuses.add(OrderStatus.PAYMENT_PENDING)
            return 1
        }

        override suspend fun markConfirmingInventory(id: UUID): Long =
            cas(id, setOf(OrderStatus.PAYMENT_PENDING, OrderStatus.CONFIRMING_INVENTORY), OrderStatus.CONFIRMING_INVENTORY)

        override suspend fun markCompensating(id: UUID, reason: String): Long {
            val o = ordersById[id] ?: return 0
            if (o.status !in setOf(OrderStatus.CONFIRMING_INVENTORY, OrderStatus.COMPENSATING)) return 0
            o.status = OrderStatus.COMPENSATING
            o.compensationReason = reason
            savedOrderStatuses.add(OrderStatus.COMPENSATING)
            return 1
        }

        override suspend fun markConfirmed(id: UUID): Long {
            if (failNextMarkConfirmed) {
                failNextMarkConfirmed = false
                throw IllegalStateException("simulated commit failure")
            }
            return cas(id, setOf(OrderStatus.CONFIRMING_INVENTORY), OrderStatus.CONFIRMED, clearToken = true)
        }

        /** The public code the terminal commit stored, so a test can assert what a customer would see. */
        @Volatile
        var storedFailureCode: CustomerFailureCode? = null

        override suspend fun markFailedUnpaid(id: UUID, failureCode: CustomerFailureCode): Long =
            cas(id, setOf(OrderStatus.RESERVING, OrderStatus.PAYMENT_PENDING), OrderStatus.FAILED, clearToken = true)
                .also { if (it > 0) storedFailureCode = failureCode }

        override suspend fun markFailedAfterReversal(id: UUID, failureCode: CustomerFailureCode): Long =
            cas(id, setOf(OrderStatus.COMPENSATING), OrderStatus.FAILED, clearToken = true)
                .also { if (it > 0) storedFailureCode = failureCode }

        override suspend fun cancelIfUnpaid(id: UUID): Long =
            cas(id, setOf(OrderStatus.ACCEPTED, OrderStatus.RESERVING), OrderStatus.CANCELLED, clearToken = true)
    }

    private class FakeReconciliationService : ReconciliationService(
        mock(ReconciliationRecordRepository::class.java),
        SimpleMeterRegistry(),
        TransactionalOperator.create(NoopTxManager),
    ) {
        val opened = CopyOnWriteArrayList<Pair<ReconciliationKind, String>>()
        val closedFor = CopyOnWriteArrayList<UUID>()

        override suspend fun recordInvariantViolation(
            orderId: UUID,
            orderState: String,
            correlationId: String,
            failureClass: String,
            detail: String?,
        ): ReconciliationRecord {
            opened.add(ReconciliationKind.INVARIANT_VIOLATION to failureClass)
            return record(orderId, ReconciliationKind.INVARIANT_VIOLATION, orderState, correlationId, failureClass)
        }

        override suspend fun recordPaymentReversalFailure(
            orderId: UUID,
            orderState: String,
            correlationId: String,
            failureClass: String,
            detail: String?,
        ): ReconciliationRecord {
            opened.add(ReconciliationKind.PAYMENT_REVERSAL_FAILURE to failureClass)
            return record(orderId, ReconciliationKind.PAYMENT_REVERSAL_FAILURE, orderState, correlationId, failureClass)
        }

        override suspend fun closeAutoResolvable(orderId: UUID): Long {
            closedFor.add(orderId)
            return 0
        }

        override fun countRequired(record: ReconciliationRecord) = Unit

        fun kindsOpened() = opened.map { it.first }

        private fun record(
            orderId: UUID,
            kind: ReconciliationKind,
            orderState: String,
            correlationId: String,
            failureClass: String,
        ) = ReconciliationRecord(
            orderId = orderId,
            kind = kind.name,
            orderState = orderState,
            correlationId = correlationId,
            failureClass = failureClass,
        )
    }

    private class StubInventoryClient(
        private val hold: () -> HoldOutcome = { HoldOutcome.Held("res-1", OffsetDateTime.now().plusMinutes(10)) },
        private val confirm: () -> ConfirmReservationOutcome = { ConfirmReservationOutcome.Confirmed("res-1") },
        /** Runs inside reserveSeats, i.e. exactly at the hold suspension point. */
        private val duringReserve: (suspend () -> Unit)? = null,
    ) : InventoryClient {
        val releasedReservationIds = CopyOnWriteArrayList<String>()
        val releaseKeys = CopyOnWriteArrayList<String>()
        val reserveCalls = CopyOnWriteArrayList<UUID>()
        val reservedTravelDates = CopyOnWriteArrayList<LocalDate>()
        val confirmCalls = CopyOnWriteArrayList<String>()

        override suspend fun reserveSeats(
            request: ReservationRequest,
            idempotencyKey: String,
            userId: String,
        ): HoldOutcome {
            reserveCalls.add(request.orderId)
            reservedTravelDates.add(request.travelDate)
            duringReserve?.invoke()
            return hold()
        }

        override suspend fun confirmReservation(reservationId: String, orderId: UUID): ConfirmReservationOutcome {
            confirmCalls.add(reservationId)
            return confirm()
        }

        override suspend fun releaseSeats(reservationId: String, idempotencyKey: String) {
            releasedReservationIds.add(reservationId)
            releaseKeys.add(idempotencyKey)
        }
    }

    private class StubPaymentClient(
        private val authorize: () -> AuthorizationOutcome = { AuthorizationOutcome.Authorized("test-auth-id") },
        private val reversal: () -> ReversalOutcome = { ReversalOutcome.Reversed },
    ) : PaymentClient {
        val authorizeCalls = CopyOnWriteArrayList<UUID>()
        /** The full requests, so what is actually charged — amount and currency — can be asserted. */
        val authorizeRequests = CopyOnWriteArrayList<PaymentRequest>()
        val refundedOrderIds = CopyOnWriteArrayList<UUID>()
        val refundKeys = CopyOnWriteArrayList<String>()

        override suspend fun authorizePayment(
            request: PaymentRequest,
            idempotencyKey: String,
            correlationId: String,
        ): AuthorizationOutcome {
            authorizeCalls.add(request.orderId)
            authorizeRequests.add(request)
            return authorize()
        }

        override suspend fun refund(orderId: UUID, idempotencyKey: String): ReversalOutcome {
            refundedOrderIds.add(orderId)
            refundKeys.add(idempotencyKey)
            return reversal()
        }
    }

    /**
     * Catalog stand-in. Answers with [OFFER] unless told otherwise; [failWith] makes the lookup throw,
     * which is how "Catalog is unreachable" is expressed — the client throws unknown outcomes rather
     * than returning them, so the checkout can only fail closed.
     */
    private class StubCatalogClient(
        var outcome: OfferOutcome = OfferOutcome.Found(OFFER),
        var failWith: Throwable? = null,
    ) : CatalogClient {
        val lookedUp = CopyOnWriteArrayList<String>()

        override suspend fun getOffer(offerId: String): OfferOutcome {
            lookedUp.add(offerId)
            failWith?.let { throw it }
            return outcome
        }
    }

    // --- harness ----------------------------------------------------------------------------------

    private fun orchestratorFor(
        service: FakeOrderService,
        inventory: StubInventoryClient = StubInventoryClient(),
        payment: StubPaymentClient = StubPaymentClient(),
        reconciliation: FakeReconciliationService = FakeReconciliationService(),
        catalog: StubCatalogClient = StubCatalogClient(),
    ) = CheckoutOrchestrator(
        orderService = service,
        inventoryClient = inventory,
        paymentClient = payment,
        catalogClient = catalog,
        outboxEventRepository = mock(OutboxEventRepository::class.java),
        reconciliationService = reconciliation,
        objectMapper = mapper,
        meterRegistry = SimpleMeterRegistry(),
        txOperator = inlineTx,
    )

    private fun order(
        id: UUID,
        status: OrderStatus = OrderStatus.ACCEPTED,
        token: String? = "test-token",
        reservationId: String? = null,
        compensationReason: String? = null,
        travelDate: LocalDate = TRAVEL_DATE,
        currency: String = "EUR",
    ) = Order(
        id = id,
        userId = "user-1",
        status = status,
        routeId = "R1",
        seats = "1A,1B",
        travelDate = travelDate,
        totalAmount = BigDecimal("100.00"),
        currency = currency,
        createdAt = LocalDateTime.now(),
        paymentMethodToken = token,
        recipientEmail = "alice@example.com",
        reservationId = reservationId,
        compensationReason = compensationReason,
    ).apply { setAsNew(false) }

    private fun event(orderId: UUID) =
        OrderPlacedEvent(correlationId = "corr-1", orderId = orderId, principalId = "user-1")

    private fun refused(code: InventoryRefusalCode): () -> ConfirmReservationOutcome =
        { ConfirmReservationOutcome.Refused(code, "detail") }

    // --- checkout entry ---------------------------------------------------------------------------

    @Test
    fun `new checkout returns ACCEPTED and persists an order plus an idempotency record`() = runTest {
        val service = FakeOrderService()

        val response = orchestratorFor(service).checkout(
            request = CheckoutRequest(routeId = "R1", seats = listOf("1A", "1B"), travelDate = TRAVEL_DATE, totalAmount = BigDecimal("100.00"), paymentMethodToken = "test-token"),
            idempotencyKey = "key-123",
            userId = "user-1",
            recipientEmail = "alice@example.com"
        )

        assertEquals(OrderStatus.ACCEPTED, response.status)
        assertEquals(OrderStatus.ACCEPTED, service.savedOrderStatuses.first())
        assertTrue(service.idempotencyStore.containsKey("key-123"))
    }

    @Test
    fun `a known idempotency key replays the stored response without creating a new order`() = runTest {
        val service = FakeOrderService()
        val storedOrderId = UUID.randomUUID()
        val storedResponse = CheckoutResponse(storedOrderId, OrderStatus.ACCEPTED)
        service.seeded = IdempotentRequest("key-abc", mapper.writeValueAsString(storedResponse), LocalDateTime.now())

        val response = orchestratorFor(service).checkout(
            request = CheckoutRequest(routeId = "R1", seats = listOf("2A"), travelDate = TRAVEL_DATE, totalAmount = BigDecimal("50.00"), paymentMethodToken = "test-token"),
            idempotencyKey = "key-abc",
            userId = "user-1",
            recipientEmail = "alice@example.com"
        )

        assertEquals(storedOrderId, response.orderId)
        assertTrue(service.savedOrderStatuses.isEmpty())
    }

    @Test
    fun `same idempotency key with a different payload is rejected with 409`() = runTest {
        val service = FakeOrderService()
        val storedResponse = CheckoutResponse(UUID.randomUUID(), OrderStatus.ACCEPTED)
        service.seeded = IdempotentRequest(
            "key-x", mapper.writeValueAsString(storedResponse), LocalDateTime.now(),
            "user-1", "checkout", "a-different-fingerprint",
        )

        val ex = assertThrows<ApiException> {
            orchestratorFor(service).checkout(
                request = CheckoutRequest(routeId = "R9", seats = listOf("9Z"), travelDate = TRAVEL_DATE, totalAmount = BigDecimal("999.00"), paymentMethodToken = "other-token"),
                idempotencyKey = "key-x",
                userId = "user-1",
                recipientEmail = "alice@example.com"
            )
        }

        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code)
        assertTrue(service.savedOrderStatuses.isEmpty())
    }

    // --- travel date (issue #64) ------------------------------------------------------------------

    @Test
    fun `the travel date reaches the Inventory hold`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val orderId = UUID.randomUUID()
        val date = LocalDate.now().plusDays(21)
        service.ordersById[orderId] = order(orderId, travelDate = date)

        orchestratorFor(service, inventory).processOrderPlaced(event(orderId))

        // Without this, Inventory keys the hold by (route, seat) alone and the seat is sold forever.
        assertEquals(listOf(date), inventory.reservedTravelDates.toList())
    }

    @Test
    fun `a checkout for a past travel date is rejected before anything is persisted`() = runTest {
        val service = FakeOrderService()

        val ex = assertThrows<ApiException> {
            orchestratorFor(service).checkout(
                request = CheckoutRequest(
                    routeId = "R1",
                    seats = listOf("1A"),
                    travelDate = LocalDate.now().minusDays(1),
                    totalAmount = BigDecimal("100.00"),
                    paymentMethodToken = "test-token",
                ),
                idempotencyKey = "key-past",
                userId = "user-1",
                recipientEmail = "alice@example.com",
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertEquals("TRAVEL_DATE_IN_THE_PAST", ex.code)
        // Rejected before any write: no order, and no idempotency record to poison the key.
        assertTrue(service.savedOrderStatuses.isEmpty())
        assertTrue(service.idempotencyStore.isEmpty())
    }

    @Test
    fun `today is an acceptable travel date`() = runTest {
        val service = FakeOrderService()

        val response = orchestratorFor(service).checkout(
            request = CheckoutRequest(
                routeId = "R1",
                seats = listOf("1A"),
                travelDate = LocalDate.now(),
                totalAmount = BigDecimal("100.00"),
                paymentMethodToken = "test-token",
            ),
            idempotencyKey = "key-today",
            userId = "user-1",
            recipientEmail = "alice@example.com",
        )

        // The guard rejects the past, not the present: a same-day departure is a real purchase.
        assertEquals(OrderStatus.ACCEPTED, response.status)
    }

    @Test
    fun `the same idempotency key with a different travel date is a conflict`() = runTest {
        val service = FakeOrderService()
        val orchestrator = orchestratorFor(service)
        fun requestFor(date: LocalDate) = CheckoutRequest(
            routeId = "R1",
            seats = listOf("1A"),
            travelDate = date,
            totalAmount = BigDecimal("100.00"),
            paymentMethodToken = "test-token",
        )

        orchestrator.checkout(requestFor(TRAVEL_DATE), "key-dates", "user-1", "alice@example.com")

        // The travel date is part of what was ordered, so it has to be part of the fingerprint. If it
        // were not, this would replay the first order and the caller would believe it had booked the
        // second date.
        val ex = assertThrows<ApiException> {
            orchestrator.checkout(requestFor(TRAVEL_DATE.plusDays(1)), "key-dates", "user-1", "alice@example.com")
        }
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code)
    }

    // --- the offer (ADR-015) ----------------------------------------------------------------------

    @Test
    fun `a checkout carrying an offerId persists it on the order`() = runTest {
        val service = FakeOrderService()

        orchestratorFor(service).checkout(
            request = CheckoutRequest(
                routeId = "R1",
                seats = listOf("1A"),
                travelDate = TRAVEL_DATE,
                totalAmount = BigDecimal("100.00"),
                paymentMethodToken = "test-token",
                offerId = "of-001",
            ),
            idempotencyKey = "key-offer",
            userId = "user-1",
            recipientEmail = "alice@example.com",
        )

        // The refund needs to know which offer was sold. Since #93 this id is also resolved against
        // Catalog, which is what the price, currency and refund-terms cases below cover.
        assertEquals("of-001", service.ordersById.values.single().offerId)
    }

    @Test
    fun `a checkout without an offerId still succeeds and stores no offer`() = runTest {
        val service = FakeOrderService()

        val response = orchestratorFor(service).checkout(
            request = CheckoutRequest(
                routeId = "R1",
                seats = listOf("1A"),
                travelDate = TRAVEL_DATE,
                totalAmount = BigDecimal("100.00"),
                paymentMethodToken = "test-token",
            ),
            idempotencyKey = "key-no-offer",
            userId = "user-1",
            recipientEmail = "alice@example.com",
        )

        // The SPA does not send the field yet (issue #84). An unrefundable order is a smaller failure
        // than a checkout that rejects every request until the frontend catches up.
        assertEquals(OrderStatus.ACCEPTED, response.status)
        assertNull(service.ordersById.values.single().offerId)
    }

    @Test
    fun `the same idempotency key with a different offerId is a conflict`() = runTest {
        val service = FakeOrderService()
        val orchestrator = orchestratorFor(service)
        fun requestFor(offerId: String) = CheckoutRequest(
            routeId = "R1",
            seats = listOf("1A"),
            travelDate = TRAVEL_DATE,
            totalAmount = BigDecimal("100.00"),
            paymentMethodToken = "test-token",
            offerId = offerId,
        )

        orchestrator.checkout(requestFor("of-001"), "key-offers", "user-1", "alice@example.com")

        // The offer is part of what was ordered — it decides the refund terms — so it belongs in the
        // fingerprint like the travel date.
        val ex = assertThrows<ApiException> {
            orchestrator.checkout(requestFor("of-002"), "key-offers", "user-1", "alice@example.com")
        }
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code)
    }

    @Test
    fun `a key stored before the offer field existed still replays`() = runTest {
        val service = FakeOrderService()
        val orchestrator = orchestratorFor(service)
        val request = CheckoutRequest(
            routeId = "R1",
            seats = listOf("1A"),
            travelDate = TRAVEL_DATE,
            totalAmount = BigDecimal("100.00"),
            paymentMethodToken = "test-token",
        )
        val firstOrderId = orchestrator.checkout(request, "key-rollout", "user-1", "alice@example.com").orderId

        // The offer segment is appended only when the field is present, so an offer-less payload
        // hashes exactly as it did before the column shipped. Idempotency records live 24 hours: if
        // the canonical string had changed for every request, every key in flight across the
        // deployment would have answered 409 to its own retry.
        val replay = orchestrator.checkout(request, "key-rollout", "user-1", "alice@example.com")

        assertEquals(firstOrderId, replay.orderId)
        assertEquals(1, service.savedOrderStatuses.size)
    }

    // --- offer resolution and the price of the sale (#93) -----------------------------------------

    /** The amount every request in this file asks for, and the price [OFFER] is seeded at. */
    private fun offerRequest(
        routeId: String = "R1",
        totalAmount: BigDecimal = BigDecimal("100.00"),
        offerId: String? = "of-001",
    ) = CheckoutRequest(
        routeId = routeId,
        seats = listOf("1A"),
        travelDate = TRAVEL_DATE,
        totalAmount = totalAmount,
        paymentMethodToken = "test-token",
        offerId = offerId,
    )

    @Test
    fun `an amount that disagrees with the offer is rejected before anything is persisted`() = runTest {
        // Issue #93: `totalAmount` used to arrive from the browser and be validated against nothing,
        // so a checkout submitted with 0.01 was authorized for one cent and confirmed as a sale.
        val service = FakeOrderService()

        val ex = assertThrows<ApiException> {
            orchestratorFor(service).checkout(
                offerRequest(totalAmount = BigDecimal("0.01")),
                "key-cheap",
                "user-1",
                "alice@example.com",
            )
        }

        assertEquals("OFFER_PRICE_MISMATCH", ex.code)
        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        // Rejected, never silently corrected to the real price: charging an amount other than the
        // one the customer was shown is its own bug. And nothing was written.
        assertTrue(service.ordersById.isEmpty())
        assertTrue(service.idempotencyStore.isEmpty())
    }

    @Test
    fun `the price that is persisted is the resolved one, not the one the client sent`() = runTest {
        val service = FakeOrderService()

        // Equal in value, different in scale. compareTo accepts it — rejecting a correct amount over
        // a trailing zero would be a bug — and what lands on the order is still Catalog's number.
        orchestratorFor(service).checkout(
            offerRequest(totalAmount = BigDecimal("100.0")),
            "key-scale",
            "user-1",
            "alice@example.com",
        )

        val stored = service.ordersById.values.single().totalAmount
        assertEquals(0, BigDecimal("100.00").compareTo(stored))
        assertEquals("100.00", stored.toPlainString(), "the request's number was persisted, not Catalog's")
    }

    @Test
    fun `an offer Catalog does not know is a 400, not a silent acceptance`() = runTest {
        val service = FakeOrderService()
        val catalog = StubCatalogClient(outcome = OfferOutcome.NotFound)

        val ex = assertThrows<ApiException> {
            orchestratorFor(service, catalog = catalog).checkout(
                offerRequest(), "key-unknown-offer", "user-1", "alice@example.com",
            )
        }

        assertEquals("OFFER_NOT_FOUND", ex.code)
        assertTrue(service.ordersById.isEmpty())
    }

    @Test
    fun `an offer sold on a different route is rejected`() = runTest {
        // Reachable from a client that keeps the chosen offer across a route change. Left unchecked,
        // the seats would be held on one route and the refund terms taken from another route's offer.
        val service = FakeOrderService()

        val ex = assertThrows<ApiException> {
            orchestratorFor(service).checkout(
                offerRequest(routeId = "R2"), "key-wrong-route", "user-1", "alice@example.com",
            )
        }

        assertEquals("OFFER_ROUTE_MISMATCH", ex.code)
        assertTrue(service.ordersById.isEmpty())
    }

    @Test
    fun `an unreachable Catalog fails the checkout closed rather than trusting the amount`() = runTest {
        // The deliberate availability trade: while Catalog is down, checkouts carrying an offer are
        // refused. The alternative — accepting the client's amount when the check cannot be made —
        // reopens #93 at exactly the moment nobody is watching.
        val service = FakeOrderService()
        val catalog = StubCatalogClient(failWith = TimeoutException("catalog is down"))

        val ex = assertThrows<ApiException> {
            orchestratorFor(service, catalog = catalog).checkout(
                offerRequest(), "key-catalog-down", "user-1", "alice@example.com",
            )
        }

        // 503 and not 400: the customer's request is fine, so the answer is "ask again", not
        // "you are wrong". Nothing was persisted, so the retry is a clean first attempt.
        assertEquals("OFFER_UNRESOLVABLE", ex.code)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status)
        assertTrue(service.ordersById.isEmpty())
    }

    @Test
    fun `a malformed currency refuses the checkout instead of poisoning the pipeline`() = runTest {
        // Payments validates the currency against ISO 4217 and answers 400, and only DECLINED is a
        // definitive outcome on the Orders side — so an unusable currency would not fail the order,
        // it would leave it in PAYMENT_PENDING holding a seat while the delivery retried to the
        // dead-letter topic. Catching it here turns catalogue data into one rejected checkout.
        val service = FakeOrderService()
        val catalog = StubCatalogClient(outcome = OfferOutcome.Found(OFFER.copy(currency = "eur")))

        val ex = assertThrows<ApiException> {
            orchestratorFor(service, catalog = catalog).checkout(
                offerRequest(), "key-bad-currency", "user-1", "alice@example.com",
            )
        }

        assertEquals("OFFER_UNRESOLVABLE", ex.code)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status)
        assertTrue(service.ordersById.isEmpty())
    }

    @Test
    fun `an offer priced at zero never reaches Payments`() = runTest {
        // Payments rejects a non-positive amount the same way, with the same consequence. A free
        // promotional offer is the plausible way this arrives, and refusing it loudly is the right
        // answer until someone decides what buying one should mean.
        val service = FakeOrderService()
        val catalog = StubCatalogClient(outcome = OfferOutcome.Found(OFFER.copy(price = BigDecimal.ZERO)))

        val ex = assertThrows<ApiException> {
            orchestratorFor(service, catalog = catalog).checkout(
                offerRequest(totalAmount = BigDecimal.ZERO), "key-zero-price", "user-1", "alice@example.com",
            )
        }

        assertEquals("OFFER_UNRESOLVABLE", ex.code)
        assertTrue(service.ordersById.isEmpty())
    }

    @Test
    fun `an unusable offer is not reported as a price mismatch`() = runTest {
        // The ordering of the two checks, which is the whole reason the guard sits where it does.
        // Comparing a garbage price against the request would answer OFFER_PRICE_MISMATCH — a 400
        // blaming the customer for a catalogue that is broken.
        val service = FakeOrderService()
        val catalog = StubCatalogClient(
            outcome = OfferOutcome.Found(OFFER.copy(price = BigDecimal("-5.00"), currency = "CH")),
        )

        val ex = assertThrows<ApiException> {
            orchestratorFor(service, catalog = catalog).checkout(
                offerRequest(totalAmount = BigDecimal("100.00")), "key-garbage", "user-1", "alice@example.com",
            )
        }

        assertEquals("OFFER_UNRESOLVABLE", ex.code)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status)
    }

    @Test
    fun `the currency comes from the offer rather than from a literal`() = runTest {
        // Latent today — every seeded offer is EUR — and not latent for long: two seeded routes are
        // cross-border, and the first CHF offer would have been authorized as the same number of
        // euros, because the amount and its unit came from two different places.
        val service = FakeOrderService()
        val catalog = StubCatalogClient(outcome = OfferOutcome.Found(OFFER.copy(currency = "CHF")))

        orchestratorFor(service, catalog = catalog).checkout(
            offerRequest(), "key-chf", "user-1", "alice@example.com",
        )

        assertEquals("CHF", service.ordersById.values.single().currency)
    }

    @Test
    fun `the refund terms and the departure instant are frozen onto the order`() = runTest {
        val service = FakeOrderService()

        orchestratorFor(service).checkout(offerRequest(), "key-terms", "user-1", "alice@example.com")

        val order = service.ordersById.values.single()
        // The rule itself, not only the version: Catalog resolves offers from a flat list of current
        // ones and keeps no history, so a version marker alone would point at terms that may since
        // have been edited (ADR-015, corrected).
        assertEquals(true, order.refundable)
        assertEquals(60, order.refundCutoffMinutesBeforeDeparture)
        assertEquals(3, order.offerTermsVersion)
        // And the cutoff has a moment to count back from. A date plus a zone-less local time is not
        // one; this is composed once here so the refund path does no zone arithmetic at all.
        assertEquals(
            TRAVEL_DATE.atTime(LocalTime.of(7, 0)).atZone(ZoneId.of("Europe/Rome")).toInstant(),
            order.departureAt?.toInstant(),
        )
    }

    @Test
    fun `a checkout without an offer resolves nothing and is priced as the client asked`() = runTest {
        val service = FakeOrderService()
        val catalog = StubCatalogClient()

        orchestratorFor(service, catalog = catalog).checkout(
            offerRequest(offerId = null), "key-offerless", "user-1", "alice@example.com",
        )

        // Catalog is not consulted at all, so an offer-less checkout keeps working while Catalog is
        // down — and its amount stays unvalidated, which is the part of #93 only the SPA can close.
        assertTrue(catalog.lookedUp.isEmpty())
        val order = service.ordersById.values.single()
        assertEquals(BigDecimal("100.00"), order.totalAmount)
        assertEquals("EUR", order.currency)
        assertNull(order.refundable)
        assertNull(order.departureAt)
    }

    @Test
    fun `a replay returns the stored result without resolving the offer again`() = runTest {
        val service = FakeOrderService()
        val catalog = StubCatalogClient()
        val orchestrator = orchestratorFor(service, catalog = catalog)
        val first = orchestrator.checkout(offerRequest(), "key-replay", "user-1", "alice@example.com")

        // Resolution sits after the idempotency check on purpose: re-resolving would make an already
        // accepted order unreplayable the moment Catalog went down or its price changed.
        catalog.failWith = TimeoutException("catalog went down after the order was accepted")
        val replay = orchestrator.checkout(offerRequest(), "key-replay", "user-1", "alice@example.com")

        assertEquals(first.orderId, replay.orderId)
        assertEquals(1, catalog.lookedUp.size)
    }

    @Test
    fun `the authorization charges the amount and currency stored on the order`() = runTest {
        // The actual fix for #93 as Payments sees it. Read off the row rather than re-resolved: the
        // pipeline is asynchronous and may resume much later, so a catalogue edit — or Catalog being
        // down — must not change what an accepted order authorizes.
        val service = FakeOrderService()
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId, currency = "CHF")

        orchestratorFor(service, payment = payment).processOrderPlaced(event(orderId))

        val authorized = payment.authorizeRequests.single()
        assertEquals(BigDecimal("100.00"), authorized.amount)
        assertEquals("CHF", authorized.currency)
    }

    // --- happy path and phase order ---------------------------------------------------------------

    @Test
    fun `a fresh order walks every phase in one delivery and confirms Inventory before committing`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory).processOrderPlaced(event(orderId))

        assertEquals(
            listOf(
                OrderStatus.RESERVING,
                OrderStatus.PAYMENT_PENDING,
                OrderStatus.CONFIRMING_INVENTORY,
                OrderStatus.CONFIRMED,
            ),
            service.savedOrderStatuses.toList(),
        )
        // Invariant 7: the confirmation happened, and it happened before the local commit.
        assertEquals(listOf("res-1"), inventory.confirmCalls.toList())
        assertNull(service.ordersById[orderId]!!.paymentMethodToken)
    }

    @Test
    fun `processOrderPlaced skips an order already in a terminal state`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId, status = OrderStatus.CONFIRMED)

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertTrue(service.savedOrderStatuses.isEmpty())
        assertTrue(inventory.reserveCalls.isEmpty())
        assertTrue(inventory.confirmCalls.isEmpty())
        assertTrue(payment.authorizeCalls.isEmpty())
    }

    // --- resume: each durable phase repeats only its own action -----------------------------------

    @Test
    fun `a resume from PAYMENT_PENDING does not repeat the hold`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId, status = OrderStatus.PAYMENT_PENDING, reservationId = "res-1")

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertTrue(inventory.reserveCalls.isEmpty())
        assertEquals(1, payment.authorizeCalls.size)
        assertEquals(1, inventory.confirmCalls.size)
        assertEquals(OrderStatus.CONFIRMED, service.ordersById[orderId]!!.status)
    }

    @Test
    fun `a resume from CONFIRMING_INVENTORY repeats only the confirmation`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId, status = OrderStatus.CONFIRMING_INVENTORY, reservationId = "res-1")

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertTrue(inventory.reserveCalls.isEmpty())
        assertTrue(payment.authorizeCalls.isEmpty())
        assertEquals(1, inventory.confirmCalls.size)
        assertEquals(OrderStatus.CONFIRMED, service.ordersById[orderId]!!.status)
    }

    @Test
    fun `a resume from COMPENSATING repeats only the reversal`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(
            orderId,
            status = OrderStatus.COMPENSATING,
            reservationId = "res-1",
            compensationReason = InventoryRefusalCode.RESERVATION_EXPIRED.name,
        )

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertTrue(inventory.reserveCalls.isEmpty())
        assertTrue(payment.authorizeCalls.isEmpty())
        assertTrue(inventory.confirmCalls.isEmpty())
        assertEquals(listOf(orderId), payment.refundedOrderIds.toList())
        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
    }

    // --- unknown outcomes: rethrow, never compensate ----------------------------------------------

    @Test
    fun `an unknown confirmation outcome is rethrown with no refund and no release`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(confirm = { throw TimeoutException("simulated timeout") })
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        assertFailsWith<TimeoutException> {
            orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))
        }

        // Invariant 8: the durable phase is preserved for the next delivery, and nothing was undone.
        assertEquals(OrderStatus.CONFIRMING_INVENTORY, service.ordersById[orderId]!!.status)
        assertTrue(payment.refundedOrderIds.isEmpty())
        assertTrue(inventory.releasedReservationIds.isEmpty())
    }

    @Test
    fun `an unknown authorization outcome is rethrown and keeps PAYMENT_PENDING`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val payment = StubPaymentClient(authorize = { throw TimeoutException("simulated timeout") })
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        assertFailsWith<TimeoutException> {
            orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))
        }

        assertEquals(OrderStatus.PAYMENT_PENDING, service.ordersById[orderId]!!.status)
        assertTrue(payment.refundedOrderIds.isEmpty())
        assertTrue(inventory.releasedReservationIds.isEmpty())
    }

    @Test
    fun `an unknown reversal outcome is rethrown and keeps COMPENSATING`() = runTest {
        val service = FakeOrderService()
        val payment = StubPaymentClient(reversal = { throw TimeoutException("simulated timeout") })
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(
            orderId,
            status = OrderStatus.COMPENSATING,
            reservationId = "res-1",
            compensationReason = InventoryRefusalCode.RESERVATION_EXPIRED.name,
        )

        assertFailsWith<TimeoutException> {
            orchestratorFor(service, StubInventoryClient(), payment).processOrderPlaced(event(orderId))
        }

        assertEquals(OrderStatus.COMPENSATING, service.ordersById[orderId]!!.status)
    }

    // --- definitive confirmation refusals ---------------------------------------------------------

    @Test
    fun `an expired reservation compensates and fails without a reconciliation record`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(confirm = refused(InventoryRefusalCode.RESERVATION_EXPIRED))
        val payment = StubPaymentClient()
        val reconciliation = FakeReconciliationService()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment, reconciliation).processOrderPlaced(event(orderId))

        assertTrue(service.savedOrderStatuses.contains(OrderStatus.COMPENSATING))
        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
        assertEquals(listOf(orderId), payment.refundedOrderIds.toList())
        assertEquals(listOf("res-1"), inventory.releasedReservationIds.toList())
        // Losing a race against the deadline is ordinary, not an invariant breach.
        assertTrue(reconciliation.kindsOpened().isEmpty())
    }

    @Test
    fun `a cancelled reservation compensates and fails without a reconciliation record`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(confirm = refused(InventoryRefusalCode.RESERVATION_CANCELLED))
        val payment = StubPaymentClient()
        val reconciliation = FakeReconciliationService()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment, reconciliation).processOrderPlaced(event(orderId))

        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
        assertEquals(listOf(orderId), payment.refundedOrderIds.toList())
        assertTrue(reconciliation.kindsOpened().isEmpty())
    }

    @Test
    fun `an ownership conflict reverses payment but must not release another order's hold`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(confirm = refused(InventoryRefusalCode.RESERVATION_ORDER_CONFLICT))
        val payment = StubPaymentClient()
        val reconciliation = FakeReconciliationService()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment, reconciliation).processOrderPlaced(event(orderId))

        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
        assertEquals(listOf(orderId), payment.refundedOrderIds.toList())
        // The reservation belongs to a DIFFERENT order: releasing it would corrupt that checkout.
        assertTrue(inventory.releasedReservationIds.isEmpty())
        assertEquals(listOf(ReconciliationKind.INVARIANT_VIOLATION), reconciliation.kindsOpened())
    }

    @Test
    fun `a missing reservation reverses payment, records an invariant violation, and releases nothing`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(confirm = refused(InventoryRefusalCode.RESERVATION_NOT_FOUND))
        val payment = StubPaymentClient()
        val reconciliation = FakeReconciliationService()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment, reconciliation).processOrderPlaced(event(orderId))

        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
        assertTrue(inventory.releasedReservationIds.isEmpty())
        assertEquals(listOf(ReconciliationKind.INVARIANT_VIOLATION), reconciliation.kindsOpened())
    }

    @Test
    fun `a seat conflict reverses payment, records an invariant violation, and releases the hold`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(confirm = refused(InventoryRefusalCode.RESERVATION_SEAT_CONFLICT))
        val payment = StubPaymentClient()
        val reconciliation = FakeReconciliationService()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment, reconciliation).processOrderPlaced(event(orderId))

        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
        // Inventory rolled the seat update back, so the hold is genuinely still ours to release.
        assertEquals(listOf("res-1"), inventory.releasedReservationIds.toList())
        assertEquals(listOf(ReconciliationKind.INVARIANT_VIOLATION), reconciliation.kindsOpened())
    }

    // --- reversal failure -------------------------------------------------------------------------

    @Test
    fun `a definitive reversal failure parks the order in COMPENSATING and does not fail it`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(confirm = refused(InventoryRefusalCode.RESERVATION_EXPIRED))
        val payment = StubPaymentClient(reversal = { ReversalOutcome.Failed("FAILED", "provider refused") })
        val reconciliation = FakeReconciliationService()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment, reconciliation).processOrderPlaced(event(orderId))

        // Unresolved money must not read as a finished order.
        assertEquals(OrderStatus.COMPENSATING, service.ordersById[orderId]!!.status)
        assertFalse(service.savedOrderStatuses.contains(OrderStatus.FAILED))
        assertEquals(listOf(ReconciliationKind.PAYMENT_REVERSAL_FAILURE), reconciliation.kindsOpened())
    }

    // --- pre-authorization failures ---------------------------------------------------------------

    @Test
    fun `an unavailable-seats rejection fails the order with no payment and no release`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(
            hold = { HoldOutcome.Rejected(InventoryRefusalCode.SEATS_UNAVAILABLE, "sold out") },
        )
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
        assertTrue(payment.authorizeCalls.isEmpty())
        assertTrue(payment.refundedOrderIds.isEmpty())
        assertTrue(inventory.releasedReservationIds.isEmpty())
    }

    @Test
    fun `a payment decline releases the hold and fails the order without a refund`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val payment = StubPaymentClient(authorize = { AuthorizationOutcome.Declined("CARD_DECLINED") })
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
        assertTrue(payment.refundedOrderIds.isEmpty()) // no money was taken
        assertEquals(listOf("res-1"), inventory.releasedReservationIds.toList())
        assertTrue(inventory.confirmCalls.isEmpty())
    }

    @Test
    fun `a missing payment token fails the order and releases the hold`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId, token = null)

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertEquals(OrderStatus.FAILED, service.ordersById[orderId]!!.status)
        assertTrue(payment.authorizeCalls.isEmpty())
        assertEquals(listOf("res-1"), inventory.releasedReservationIds.toList())
    }

    // --- crash between the Inventory sale and the local commit ------------------------------------

    @Test
    fun `a lost local commit after Inventory confirmed converges on redelivery`() = runTest {
        val service = FakeOrderService().apply { failNextMarkConfirmed = true }
        val inventory = StubInventoryClient()
        val payment = StubPaymentClient()
        val orchestrator = orchestratorFor(service, inventory, payment)
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        assertFailsWith<IllegalStateException> { orchestrator.processOrderPlaced(event(orderId)) }

        // The phase is preserved and nothing was compensated, even though Inventory already sold.
        assertEquals(OrderStatus.CONFIRMING_INVENTORY, service.ordersById[orderId]!!.status)
        assertTrue(payment.refundedOrderIds.isEmpty())
        assertTrue(inventory.releasedReservationIds.isEmpty())

        orchestrator.processOrderPlaced(event(orderId)) // Kafka redelivery

        assertEquals(OrderStatus.CONFIRMED, service.ordersById[orderId]!!.status)
        assertEquals(2, inventory.confirmCalls.size) // the idempotent replay
        assertEquals(1, payment.authorizeCalls.size) // the completed stage was not restarted
        assertEquals(1, inventory.reserveCalls.size)
    }

    // --- cancellation races -----------------------------------------------------------------------

    @Test
    fun `a cancellation that lands while the hold is in flight releases it and stays CANCELLED`() = runTest {
        val service = FakeOrderService()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)
        // Cancel exactly at the hold suspension point, with no threads or latches involved.
        val inventory = StubInventoryClient(duringReserve = { service.cancelIfUnpaid(orderId) })
        val payment = StubPaymentClient()

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertEquals(OrderStatus.CANCELLED, service.ordersById[orderId]!!.status)
        // The reservation id was never stored, so only the pipeline could release this hold.
        assertEquals(listOf("res-1"), inventory.releasedReservationIds.toList())
        assertEquals(listOf("rel-$orderId"), inventory.releaseKeys.toList())
        assertTrue(payment.authorizeCalls.isEmpty())
    }

    @Test
    fun `cancel is accepted while the order is still unpaid and releases held seats`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId, status = OrderStatus.RESERVING, reservationId = "res-held")

        val cancelled = orchestratorFor(service, inventory).cancel(orderId)

        assertEquals(OrderStatus.CANCELLED, cancelled.status)
        assertEquals(listOf("res-held"), inventory.releasedReservationIds.toList())
        assertEquals(listOf("rel-$orderId"), inventory.releaseKeys.toList())
    }

    @Test
    fun `cancel is refused once payment authorization has started`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId, status = OrderStatus.PAYMENT_PENDING, reservationId = "res-held")

        val ex = assertThrows<ApiException> { orchestratorFor(service, inventory).cancel(orderId) }

        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("ORDER_NOT_CANCELLABLE", ex.code)
        assertEquals(OrderStatus.PAYMENT_PENDING, service.ordersById[orderId]!!.status)
        // Refusing must not touch the hold: the payment may be about to succeed.
        assertTrue(inventory.releasedReservationIds.isEmpty())
    }

    @Test
    fun `cancel on a confirmed order is rejected with 409`() = runTest {
        val service = FakeOrderService()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId, status = OrderStatus.CONFIRMED)

        val ex = assertThrows<ApiException> { orchestratorFor(service).cancel(orderId) }

        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("ORDER_NOT_CANCELLABLE", ex.code)
        assertEquals(OrderStatus.CONFIRMED, service.ordersById[orderId]!!.status)
    }

    @Test
    fun `cancel on an accepted order without a hold does not attempt a release`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        val cancelled = orchestratorFor(service, inventory).cancel(orderId)

        assertEquals(OrderStatus.CANCELLED, cancelled.status)
        assertFalse(inventory.releasedReservationIds.any())
    }

    // --- idempotency keys -------------------------------------------------------------------------

    @Test
    fun `compensation uses the documented idempotency keys`() = runTest {
        val service = FakeOrderService()
        val inventory = StubInventoryClient(confirm = refused(InventoryRefusalCode.RESERVATION_EXPIRED))
        val payment = StubPaymentClient()
        val orderId = UUID.randomUUID()
        service.ordersById[orderId] = order(orderId)

        orchestratorFor(service, inventory, payment).processOrderPlaced(event(orderId))

        assertEquals(listOf("refund-$orderId"), payment.refundKeys.toList())
        assertEquals(listOf("rel-$orderId"), inventory.releaseKeys.toList())
    }
}
