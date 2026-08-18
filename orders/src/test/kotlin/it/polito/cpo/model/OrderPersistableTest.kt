package it.polito.cpo.model

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Minimal domain tests. No Spring context, no database.
 *
 * `Order` implements Persistable: R2DBC uses isNew() to decide INSERT vs UPDATE, and the checkout
 * pipeline flips the flag with setAsNew(...). These tests lock that behaviour.
 */
class OrderPersistableTest {

    private fun newOrder(id: UUID = UUID.randomUUID()) = Order(
        id = id,
        userId = "user-1",
        status = OrderStatus.ACCEPTED,
        routeId = "R1",
        seats = "1A,1B",
        totalAmount = BigDecimal("100.00"),
        createdAt = LocalDateTime.now()
    )

    @Test
    fun `a freshly constructed order is new so R2DBC performs an insert`() {
        assertTrue(newOrder().isNew())
    }

    @Test
    fun `setAsNew(false) clears the flag so R2DBC performs an update`() {
        val order = newOrder()
        order.setAsNew(false)
        assertFalse(order.isNew())
    }

    @Test
    fun `getId returns the id supplied at construction`() {
        val id = UUID.randomUUID()
        assertEquals(id, newOrder(id).getId())
    }
}
