package it.polito.cpo.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Table("orders")
class Order(
    @Id private val id: UUID,
    val userId: String,
    var status: OrderStatus,
    val routeId: String,
    val seats: String, // comma-separated seats
    // The day being travelled, which together with routeId and seats identifies what was bought.
    // Without it a seat is sold once and for all rather than for one run (issue #64).
    val travelDate: LocalDate,
    val totalAmount: BigDecimal,
    val createdAt: LocalDateTime,
    // Sandbox payment reference kept only until the order reaches a terminal state, so the
    // asynchronous pipeline can reconstruct the payment request. Cleared on CONFIRMED/FAILED.
    var paymentMethodToken: String? = null,
    // Delivery address for the confirmation/failure notification, captured from the JWT `email`
    // claim at checkout (the async pipeline has no JWT in scope). Not an ownership key.
    val recipientEmail: String? = null,
    // Inventory reservation id, set once seats are held in the async pipeline. Enables a
    // pre-payment cancellation to release the held seats. Null before RESERVING completes.
    var reservationId: String? = null,
    // Why the checkout entered COMPENSATING, so a redelivery that resumes after a crash can pick
    // the right reconciliation record kind and know whether releasing the hold is safe at all.
    // Null outside COMPENSATING.
    var compensationReason: String? = null,
) : Persistable<UUID> {

    @Transient
    private var isNew: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNew

    fun setAsNew(isNew: Boolean): Order {
        this.isNew = isNew
        return this
    }
}
