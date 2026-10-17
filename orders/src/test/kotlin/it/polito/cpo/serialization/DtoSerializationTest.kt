package it.polito.cpo.serialization

import it.polito.cpo.controller.dtos.CheckoutRequest
import it.polito.cpo.controller.dtos.CheckoutResponse
import it.polito.cpo.model.OrderStatus
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Minimal serialization test. The checkout idempotency store persists a serialized CheckoutResponse
 * and replays it by deserializing on a retry, so this round-trip is a real correctness contract.
 */
class DtoSerializationTest {

    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `CheckoutResponse survives a JSON round-trip`() {
        val original = CheckoutResponse(UUID.randomUUID(), OrderStatus.CONFIRMED)

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, CheckoutResponse::class.java)

        assertEquals(original, restored)
    }

    @Test
    fun `a payment submission replays as PAYMENT_PENDING`() {
        // The payment endpoint stores the same response type under its own key (ADR-016).
        val original = CheckoutResponse(UUID.randomUUID(), OrderStatus.PAYMENT_PENDING)

        val restored = mapper.readValue(mapper.writeValueAsString(original), CheckoutResponse::class.java)

        assertEquals(original, restored)
    }

    @Test
    fun `a checkout request without a payment token decodes as the customer-hold flow`() {
        val json = """{"routeId":"R1","seats":["1A"],"travelDate":"2026-12-01","totalAmount":24.90,"offerId":"of-001"}"""

        val request = mapper.readValue(json, CheckoutRequest::class.java)

        assertNull(request.paymentMethodToken)
    }
}
