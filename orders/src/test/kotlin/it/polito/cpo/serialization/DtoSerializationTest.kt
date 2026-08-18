package it.polito.cpo.serialization

import it.polito.cpo.controller.dtos.CheckoutResponse
import it.polito.cpo.model.OrderStatus
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.UUID
import kotlin.test.assertEquals

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
}
