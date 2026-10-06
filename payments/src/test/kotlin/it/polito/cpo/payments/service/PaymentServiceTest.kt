package it.polito.cpo.payments.service

import tools.jackson.databind.ObjectMapper
import it.polito.cpo.contracts.payments.PaymentResponse
import it.polito.cpo.contracts.payments.PaymentStatus
import it.polito.cpo.payments.model.IdempotencyRecord
import it.polito.cpo.payments.model.OutboxEvent
import it.polito.cpo.payments.model.PaymentAuthorization
import it.polito.cpo.payments.model.PaymentRefund
import it.polito.cpo.payments.repository.IdempotencyRepository
import it.polito.cpo.payments.repository.OutboxEventRepository
import it.polito.cpo.payments.repository.PaymentAuthorizationRepository
import it.polito.cpo.payments.repository.PaymentRefundRepository
import it.polito.cpo.payments.service.payment.PaymentService
import it.polito.cpo.payments.service.provider.IPaymentProvider
import it.polito.cpo.payments.service.provider.ProviderAuthorizationResult
import it.polito.cpo.payments.service.provider.ProviderRefundResult
import it.polito.cpo.observability.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import it.polito.cpo.payments.service.payment.IPaymentService

class PaymentServiceTest {

    private val providerAdapter = FakePaymentProvider()
    private val idempotencyRepository = FakeIdempotencyRepository()
    private val paymentAuthorizationRepository = FakePaymentAuthorizationRepository()
    private val paymentRefundRepository = FakePaymentRefundRepository()
    private val outboxEventRepository = FakeOutboxEventRepository()

    private val objectMapper = org.mockito.Mockito.mock(ObjectMapper::class.java).apply {
        org.mockito.Mockito.`when`(this.writeValueAsString(org.mockito.Mockito.any())).thenReturn("{}")
        org.mockito.Mockito.`when`(this.readValue(org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(PaymentResponse::class.java)))
            .thenReturn(PaymentResponse(PaymentStatus.AUTHORIZED, "ch_cached"))
    }

    private val paymentService = PaymentService(
        providerAdapter,
        idempotencyRepository as IdempotencyRepository,
        paymentAuthorizationRepository as PaymentAuthorizationRepository,
        paymentRefundRepository as PaymentRefundRepository,
        outboxEventRepository as OutboxEventRepository,
        objectMapper,
        NoopReactiveTransactionManager()
    )

    @Test
    fun `authorize - successful payment hold`() = runTest {
        val response = paymentService.authorize("order-123", "user1", BigDecimal.TEN, "EUR", "tok_visa", "key-123")
        assertEquals(PaymentStatus.AUTHORIZED, response.status)
        assertEquals("ch_123", response.providerReference)
        assertEquals(1, paymentAuthorizationRepository.saved.size)
        assertEquals(1, idempotencyRepository.saved.size)

        assertEquals(1, outboxEventRepository.saved.size)
        val row = outboxEventRepository.saved.single()
        assertEquals("payment-authorized", row.type)
        assertEquals("order-123", row.aggregateId)
    }

    @Test
    fun `refund - successful payment refund`() = runTest {
        val auth = PaymentAuthorization(
            orderId = "order-123",
            principalId = "user1",
            amount = BigDecimal.TEN,
            currency = "EUR",
            status = PaymentAuthorization.STATUS_AUTHORIZED,
            providerReference = "ch_123",
            idempotencyKey = "key-123"
        )
        paymentAuthorizationRepository.saved.add(auth)

        val response = paymentService.refund("order-123", BigDecimal.TEN, "key-refund-123")
        assertEquals(IPaymentService.RefundStatus.REFUNDED, response.status)
        assertEquals(1, paymentRefundRepository.saved.size)
        assertEquals(1, idempotencyRepository.saved.size)

        val row = outboxEventRepository.saved.single()
        assertEquals("payment-refunded", row.type)
        assertEquals("order-123", row.aggregateId)
    }

    @Test
    fun `refund - a declined refund is not cached, so an identical retry is a real attempt`() = runTest {
        paymentAuthorizationRepository.saved.add(
            PaymentAuthorization(
                orderId = "order-123",
                principalId = "user1",
                amount = BigDecimal.TEN,
                currency = "EUR",
                status = PaymentAuthorization.STATUS_AUTHORIZED,
                providerReference = "ch_123",
                idempotencyKey = "key-123"
            )
        )
        providerAdapter.refundSucceeds = false

        val first = paymentService.refund("order-123", BigDecimal.TEN, "pay-refund-order-123")
        val second = paymentService.refund("order-123", BigDecimal.TEN, "pay-refund-order-123")

        assertEquals(IPaymentService.RefundStatus.FAILED, first.status)
        assertEquals(IPaymentService.RefundStatus.FAILED, second.status)
        // The point: the retry reached the provider instead of replaying the decline.
        assertEquals(2, providerAdapter.refundCalls)
        assertEquals(0, idempotencyRepository.saved.size)
        // Both attempts on record, keyless so the FK holds.
        assertEquals(2, paymentRefundRepository.saved.size)
        org.junit.jupiter.api.Assertions.assertTrue(paymentRefundRepository.saved.all { it.idempotencyKey == null })
    }

    @Test
    fun `refund - a successful refund is still replayed rather than refunding twice`() = runTest {
        // The property the key exists for, which the fix must not cost us.
        org.mockito.Mockito.`when`(
            objectMapper.readValue(
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.eq(it.polito.cpo.payments.dto.PaymentRefundResponse::class.java)
            )
        ).thenReturn(
            it.polito.cpo.payments.dto.PaymentRefundResponse(IPaymentService.RefundStatus.REFUNDED, "re_123", null)
        )
        paymentAuthorizationRepository.saved.add(
            PaymentAuthorization(
                orderId = "order-123",
                principalId = "user1",
                amount = BigDecimal.TEN,
                currency = "EUR",
                status = PaymentAuthorization.STATUS_AUTHORIZED,
                providerReference = "ch_123",
                idempotencyKey = "key-123"
            )
        )

        paymentService.refund("order-123", BigDecimal.TEN, "pay-refund-order-123")
        val replay = paymentService.refund("order-123", BigDecimal.TEN, "pay-refund-order-123")

        assertEquals(IPaymentService.RefundStatus.REFUNDED, replay.status)
        assertEquals(1, providerAdapter.refundCalls)
        assertEquals(1, paymentRefundRepository.saved.size)
        assertEquals(1, idempotencyRepository.saved.size)
    }

    @Test
    fun `refund - classifies the two idempotency namespaces and persists the reason`() = runTest {
        val auth = PaymentAuthorization(
            orderId = "order-123",
            principalId = "user1",
            amount = BigDecimal.TEN,
            currency = "EUR",
            status = PaymentAuthorization.STATUS_AUTHORIZED,
            providerReference = "ch_123",
            idempotencyKey = "key-123"
        )
        paymentAuthorizationRepository.saved.add(auth)

        paymentService.refund("order-123", BigDecimal.TEN, "pay-refund-order-123")
        assertEquals("COMMERCIAL", paymentRefundRepository.saved.last().reason)

        paymentService.refund("order-123", BigDecimal.TEN, "refund-order-123")
        assertEquals("COMPENSATION", paymentRefundRepository.saved.last().reason)
    }

    @Test
    fun `authorize - returns cached response on idempotency match`() = runTest {
        val record = IdempotencyRecord(
            idempotencyKey = "key-idem",
            operation = "authorize",
            principalId = "user1",
            requestFingerprint = "order-123-10-EUR-tok_123",
            responseCode = 200,
            responseBody = """{"status":"AUTHORIZED","providerReference":"ch_cached"}""",
            createdAt = LocalDateTime.now()
        )
        idempotencyRepository.saved.add(record)

        val response = paymentService.authorize("order-123", "user1", BigDecimal.TEN, "EUR", "tok_123", "key-idem")
        assertEquals("ch_cached", response.providerReference)
    }

    @Test
    fun `authorize - returns conflict on idempotency mismatch`() = runTest {
        val record = IdempotencyRecord(
            idempotencyKey = "key-idem-conflict",
            operation = "authorize",
            principalId = "user1",
            requestFingerprint = "order-123-20-EUR-tok_123", // different amount
            responseCode = 200,
            responseBody = "{}",
            createdAt = LocalDateTime.now()
        )
        idempotencyRepository.saved.add(record)

        val response = paymentService.authorize("order-123", "user1", BigDecimal.TEN, "EUR", "tok_123", "key-idem-conflict")
        assertEquals(PaymentStatus.CONFLICT, response.status)
    }

    @Test
    fun `authorize - rejects zero or negative amount as a 400, without calling the provider`() = runTest {
        val zero = assertThrows<ApiException> {
            paymentService.authorize("order-123", "user1", BigDecimal.ZERO, "EUR", "tok_123", "key-zero")
        }
        assertEquals(HttpStatus.BAD_REQUEST, zero.status)
        assertEquals("INVALID_AMOUNT", zero.code)

        val negative = assertThrows<ApiException> {
            paymentService.authorize("order-123", "user1", BigDecimal("-10"), "EUR", "tok_123", "key-neg")
        }
        assertEquals(HttpStatus.BAD_REQUEST, negative.status)
        assertEquals("INVALID_AMOUNT", negative.code)

        assertEquals(0, paymentAuthorizationRepository.saved.size)
        assertEquals(0, idempotencyRepository.saved.size)
    }

    @Test
    fun `authorize - rejects malformed currency as a 400, without calling the provider`() = runTest {
        val ex = assertThrows<ApiException> {
            paymentService.authorize("order-123", "user1", BigDecimal.TEN, "euros", "tok_123", "key-badcur")
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertEquals("INVALID_CURRENCY", ex.code)
        assertEquals(0, paymentAuthorizationRepository.saved.size)
    }

    @Test
    fun `refund - rejects zero or negative amount as a 400, without calling the provider`() = runTest {
        val ex = assertThrows<ApiException> {
            paymentService.refund("order-123", BigDecimal.ZERO, "key-refund-zero")
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertEquals("INVALID_AMOUNT", ex.code)
        assertEquals(0, paymentRefundRepository.saved.size)
    }

    @Test
    fun `refund - null amount (full refund) is still accepted`() = runTest {
        val auth = PaymentAuthorization(
            orderId = "order-123",
            principalId = "user1",
            amount = BigDecimal.TEN,
            currency = "EUR",
            status = PaymentAuthorization.STATUS_AUTHORIZED,
            providerReference = "ch_123",
            idempotencyKey = "key-123"
        )
        paymentAuthorizationRepository.saved.add(auth)

        val response = paymentService.refund("order-123", null, "key-refund-full")
        assertEquals(IPaymentService.RefundStatus.REFUNDED, response.status)
    }

    @Test
    fun `publishEvent - serializes the typed event with java-time fields to a JSON string via the real ObjectMapper`() = runTest {
        // Real (non-mocked) ObjectMapper: must round-trip occurredAt (a LocalDateTime) without crashing.
        val realObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val localOutboxEventRepository = FakeOutboxEventRepository()
        val service = PaymentService(
            FakePaymentProvider(),
            FakeIdempotencyRepository() as IdempotencyRepository,
            FakePaymentAuthorizationRepository() as PaymentAuthorizationRepository,
            FakePaymentRefundRepository() as PaymentRefundRepository,
            localOutboxEventRepository as OutboxEventRepository,
            realObjectMapper,
            NoopReactiveTransactionManager()
        )

        service.authorize("order-json", "user1", BigDecimal.TEN, "EUR", "tok_visa", "key-json")

        val row = localOutboxEventRepository.saved.single()
        assertEquals("payment-authorized", row.type)
        assertEquals("order-json", row.aggregateId)

        val parsed = realObjectMapper.readTree(row.payload.asString())
        assertEquals("payment-authorized", parsed.get("eventType").asText())
        assertEquals("order-json", parsed.get("orderId").asText())
        // occurredAt is a LocalDateTime serialized by the real Jackson 3 mapper (ISO-8601, no crash).
        org.junit.jupiter.api.Assertions.assertTrue(parsed.hasNonNull("occurredAt"))
        // typed payload fields are present and nested under `payload`.
        org.junit.jupiter.api.Assertions.assertTrue(parsed.get("payload").has("amount"))
        assertEquals("AUTHORIZED", parsed.get("payload").get("status").asText())
    }

    class FakePaymentProvider : IPaymentProvider {
        var refundSucceeds = true
        var refundCalls = 0

        override suspend fun authorize(amount: BigDecimal, currency: String, token: String, idempotencyKey: String) =
            ProviderAuthorizationResult(true, "ch_123", null)

        override suspend fun refund(ref: String, amount: BigDecimal?, idempotencyKey: String): ProviderRefundResult {
            refundCalls++
            return if (refundSucceeds) ProviderRefundResult(true, "re_123", null)
            else ProviderRefundResult(false, null, "CARD_DECLINED")
        }
    }

    // A minimal proxy that implements the Spring Data repository interface by throwing exceptions 
    // for everything except what we explicitly override. This avoids Mockito suspend function bugs!
    open class FakeIdempotencyRepository : IdempotencyRepository by org.mockito.Mockito.mock(IdempotencyRepository::class.java) {
        val saved = mutableListOf<IdempotencyRecord>()
        override suspend fun findById(id: String): IdempotencyRecord? = saved.find { it.getId() == id }
        override suspend fun <S : IdempotencyRecord> save(entity: S): S { saved.add(entity); return entity }
    }

    open class FakePaymentAuthorizationRepository : PaymentAuthorizationRepository by org.mockito.Mockito.mock(PaymentAuthorizationRepository::class.java) {
        val saved = mutableListOf<PaymentAuthorization>()
        override suspend fun findByOrderId(orderId: String): PaymentAuthorization? = saved.find { it.orderId == orderId }
        override suspend fun <S : PaymentAuthorization> save(entity: S): S { saved.add(entity); return entity }
    }

    open class FakePaymentRefundRepository : PaymentRefundRepository by org.mockito.Mockito.mock(PaymentRefundRepository::class.java) {
        val saved = mutableListOf<PaymentRefund>()
        override suspend fun <S : PaymentRefund> save(entity: S): S { saved.add(entity); return entity }
    }

    open class FakeOutboxEventRepository : OutboxEventRepository by org.mockito.Mockito.mock(OutboxEventRepository::class.java) {
        val saved = mutableListOf<OutboxEvent>()
        override suspend fun <S : OutboxEvent> save(entity: S): S { saved.add(entity); return entity }
    }

    // Runs the transactional block without a real DB transaction, since these are in-memory fakes.
    class NoopReactiveTransactionManager : ReactiveTransactionManager {
        private val transaction = object : ReactiveTransaction {
            override fun isNewTransaction(): Boolean = true
            override fun setRollbackOnly() {}
            override fun isRollbackOnly(): Boolean = false
            override fun isCompleted(): Boolean = false
        }

        override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> = Mono.just(transaction)
        override fun commit(transaction: ReactiveTransaction): Mono<Void> = Mono.empty()
        override fun rollback(transaction: ReactiveTransaction): Mono<Void> = Mono.empty()
    }
}
