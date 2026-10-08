package it.polito.cpo.service

import it.polito.cpo.observability.CorrelationId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pins the shape of one audit line (DoD §5.9): the four fields the item names — correlation id,
 * principal, order, outcome — and that writing one never disturbs the caller's MDC.
 */
class MoneyPathAuditTest {

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `a line carries action, outcome, principal and order as fields, and the correlation id from the MDC`() {
        val orderId = UUID.randomUUID()
        AuditLogCapture().use { audit ->
            MoneyPathAudit.record(
                MoneyPathAudit.Action.CHECKOUT, MoneyPathAudit.Outcome.ACCEPTED, "user-1", orderId, "corr-1",
            )

            val line = audit.lines.single()
            assertEquals(
                mapOf(
                    "action" to "checkout",
                    "outcome" to "accepted",
                    "principalId" to "user-1",
                    "orderId" to orderId.toString(),
                ),
                line.fields,
            )
            assertEquals("corr-1", line.correlationId)
            assertEquals("audit checkout accepted", line.message)
        }
    }

    @Test
    fun `reason and a different owner are added only when present`() {
        AuditLogCapture().use { audit ->
            MoneyPathAudit.record(
                MoneyPathAudit.Action.REFUND, MoneyPathAudit.Outcome.ACCEPTED, "staff-1", UUID.randomUUID(), "c",
                ownerId = "user-1",
            )
            MoneyPathAudit.record(
                MoneyPathAudit.Action.REFUND, MoneyPathAudit.Outcome.ACCEPTED, "user-1", UUID.randomUUID(), "c",
                ownerId = "user-1",
            )
            MoneyPathAudit.record(
                MoneyPathAudit.Action.CHECKOUT, MoneyPathAudit.Outcome.REJECTED, "user-1", null, "c",
                reason = "PRICE_MISMATCH",
            )

            val (staff, owner, rejected) = audit.lines
            assertEquals("user-1", staff.fields["ownerId"])
            assertFalse("ownerId" in owner.fields, "the owner acting on their own order is not repeated")
            assertEquals("PRICE_MISMATCH", rejected.fields["reason"])
            assertNull(rejected.fields["orderId"], "a checkout refused before it became an order has no order id")
        }
    }

    @Test
    fun `an empty MDC is left empty`() {
        AuditLogCapture().use {
            MoneyPathAudit.record(
                MoneyPathAudit.Action.CANCEL, MoneyPathAudit.Outcome.CANCELLED, "user-1", UUID.randomUUID(), "corr-1",
            )
        }
        assertNull(MDC.get(CorrelationId.CONTEXT_KEY))
    }

    @Test
    fun `a listener's correlation id in the MDC survives a line written under another one`() {
        MDC.put(CorrelationId.CONTEXT_KEY, "listener-corr")
        AuditLogCapture().use { audit ->
            MoneyPathAudit.record(
                MoneyPathAudit.Action.CHECKOUT_PIPELINE, MoneyPathAudit.Outcome.CONFIRMED, "user-1",
                UUID.randomUUID(), "event-corr",
            )
            assertEquals("event-corr", audit.lines.single().correlationId)
        }
        assertEquals("listener-corr", MDC.get(CorrelationId.CONTEXT_KEY))
    }

    @Test
    fun `without a correlation id of its own the line uses the one already in the MDC`() {
        MDC.put(CorrelationId.CONTEXT_KEY, "listener-corr")
        AuditLogCapture().use { audit ->
            MoneyPathAudit.record(
                MoneyPathAudit.Action.REFUND_PIPELINE, MoneyPathAudit.Outcome.REFUNDED, "user-1",
                UUID.randomUUID(), null,
            )
            assertEquals("listener-corr", audit.lines.single().correlationId)
        }
        assertEquals("listener-corr", MDC.get(CorrelationId.CONTEXT_KEY))
    }
}
