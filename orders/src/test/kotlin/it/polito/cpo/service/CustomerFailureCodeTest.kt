package it.polito.cpo.service

import it.polito.cpo.client.InventoryRefusalCode
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

/**
 * Pins the property that makes [CustomerFailureCode] safe to return from a public API: the mapping is
 * an **allowlist**, so any internal failure class that is not explicitly published collapses to
 * `CHECKOUT_FAILED`.
 *
 * Without this test the safety is a convention. Someone adding an `InventoryRefusalCode` — or a new
 * `FailureClassifier` label — and forgetting this file would leak an internal state name to a
 * customer, and nothing would fail.
 */
class CustomerFailureCodeTest {

    @Test
    fun `only the seats refusal is published from the inventory vocabulary`() {
        InventoryRefusalCode.entries.forEach { code ->
            val expected = if (code == InventoryRefusalCode.SEATS_UNAVAILABLE) {
                CustomerFailureCode.SEATS_UNAVAILABLE
            } else {
                CustomerFailureCode.CHECKOUT_FAILED
            }
            assertEquals(
                expected,
                CustomerFailureCode.from(FailureClassifier.forInventoryRefusal(code)),
                "InventoryRefusalCode.$code must not reach a customer as anything but $expected",
            )
        }
    }

    @Test
    fun `invariant violations are never distinguishable to a customer`() {
        // The three that mean "Orders and Inventory disagree about a reservation". These are operator
        // incidents; naming them to a customer would describe internal state and, for an ownership
        // conflict, hint at another customer's checkout.
        listOf(
            InventoryRefusalCode.RESERVATION_ORDER_CONFLICT,
            InventoryRefusalCode.RESERVATION_SEAT_CONFLICT,
            InventoryRefusalCode.RESERVATION_NOT_FOUND,
        ).forEach {
            assertSame(
                CustomerFailureCode.CHECKOUT_FAILED,
                CustomerFailureCode.from(FailureClassifier.forInventoryRefusal(it)),
            )
        }
    }

    @Test
    fun `the payment vocabulary publishes only the decline`() {
        assertSame(
            CustomerFailureCode.PAYMENT_DECLINED,
            CustomerFailureCode.from(FailureClassifier.PAYMENT_DECLINED),
        )
        listOf(
            FailureClassifier.PAYMENT_TOKEN_MISSING,
            FailureClassifier.PAYMENT_REVERSAL_FAILED,
            FailureClassifier.DEPENDENCY_ERROR,
            FailureClassifier.POISON_PAYLOAD,
            FailureClassifier.UNKNOWN,
            FailureClassifier.CANCELLED,
        ).forEach {
            assertSame(CustomerFailureCode.CHECKOUT_FAILED, CustomerFailureCode.from(it), it)
        }
    }

    @Test
    fun `an unmapped or absent label collapses instead of throwing`() {
        assertSame(CustomerFailureCode.CHECKOUT_FAILED, CustomerFailureCode.from(null))
        assertSame(CustomerFailureCode.CHECKOUT_FAILED, CustomerFailureCode.from("a_label_invented_later"))
    }

    @Test
    fun `the compensation path stores the uppercase enum name and still maps`() {
        // stageCompensate passes Order.compensationReason, which is InventoryRefusalCode.name —
        // uppercase — while failUnpaid passes the lowercase FailureClassifier label. Both must work.
        assertSame(
            CustomerFailureCode.SEATS_UNAVAILABLE,
            CustomerFailureCode.from(InventoryRefusalCode.SEATS_UNAVAILABLE.name),
        )
        assertSame(
            CustomerFailureCode.CHECKOUT_FAILED,
            CustomerFailureCode.from(InventoryRefusalCode.RESERVATION_EXPIRED.name),
        )
    }

    @Test
    fun `a stored value round-trips, and an unknown stored value degrades instead of failing the read`() {
        CustomerFailureCode.entries.forEach {
            assertSame(it, CustomerFailureCode.parse(it.name))
        }
        assertSame(null, CustomerFailureCode.parse(null))
        assertSame(CustomerFailureCode.CHECKOUT_FAILED, CustomerFailureCode.parse("WRITTEN_BY_A_NEWER_VERSION"))
    }
}
