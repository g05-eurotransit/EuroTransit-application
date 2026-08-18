package it.polito.cpo.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("idempotency_keys")
class IdempotentRequest(
    @Id private val key: String,
    val responseBody: String,
    val createdAt: LocalDateTime,
    // Scope metadata per api-design.md: lets a reuse with a different payload be detected (409).
    // Nullable to tolerate rows written before the V3 migration.
    val principalId: String? = null,
    val operation: String? = null,
    val requestFingerprint: String? = null,
) : Persistable<String> {

    @Transient
    private var isNew: Boolean = true

    override fun getId(): String = key

    override fun isNew(): Boolean = isNew

    fun setAsNew(isNew: Boolean): IdempotentRequest {
        this.isNew = isNew
        return this
    }
}
