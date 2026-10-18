package it.polito.cpo.payments.service.payment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RefundReasonTest {

    @Test
    fun `the commercial namespace wins over the compensation prefix it contains`() {
        assertEquals(RefundReason.COMMERCIAL, RefundReason.fromIdempotencyKey("pay-refund-order-1"))
    }

    @Test
    fun `the compensation namespace maps to COMPENSATION`() {
        assertEquals(RefundReason.COMPENSATION, RefundReason.fromIdempotencyKey("refund-order-1"))
    }

    @Test
    fun `an unrecognised key is UNKNOWN rather than a guess`() {
        assertEquals(RefundReason.UNKNOWN, RefundReason.fromIdempotencyKey("some-other-key"))
    }
}
