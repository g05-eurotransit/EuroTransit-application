package it.polito.cpo.payments.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.domain.Persistable
import org.springframework.data.annotation.Transient
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Table("payment_refunds")
data class PaymentRefund(
    @Id
    private val id: UUID = UUID.randomUUID(),
    val authorizationId: UUID,
    val amount: BigDecimal?,
    val currency: String,
    val status: String,
    val providerReference: String?,
    // Null on a decline: no idempotency record is written for one, and this has a FK to that table.
    val idempotencyKey: String?,
    // The key the caller sent, on every attempt. No FK, so a decline keeps it.
    val requestKey: String? = null,
    val reason: String, // "COMPENSATION" | "COMMERCIAL" | "UNKNOWN"
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
) : Persistable<UUID> {
    
    @Transient
    private var isNewRecord: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNewRecord

    fun setAsNew(isNew: Boolean) {
        this.isNewRecord = isNew
    }

    companion object {
        const val STATUS_REFUNDED = "REFUNDED"
        const val STATUS_FAILED = "FAILED"
    }
}
