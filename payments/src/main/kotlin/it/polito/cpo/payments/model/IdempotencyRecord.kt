package it.polito.cpo.payments.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

import org.springframework.data.domain.Persistable
import org.springframework.data.annotation.Transient

@Table("idempotency_records")
data class IdempotencyRecord(
    @Id
    private val idempotencyKey: String,
    val operation: String,
    val principalId: String,
    val requestFingerprint: String,
    val responseCode: Int,
    val responseBody: String?,
    val createdAt: LocalDateTime = LocalDateTime.now()
) : Persistable<String> {
    
    @Transient
    private var isNewRecord: Boolean = true

    override fun getId(): String = idempotencyKey

    override fun isNew(): Boolean = isNewRecord

    fun setAsNew(isNew: Boolean) {
        this.isNewRecord = isNew
    }
}
