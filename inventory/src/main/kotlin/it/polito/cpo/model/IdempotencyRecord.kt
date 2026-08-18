package it.polito.cpo.model

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime

@Table("idempotency_records")
data class IdempotencyRecord(
    @Id
    @get:JvmName("getIdempotencyKeyValue")
    val idempotencyKey: String,

    val principalId: String,
    val operation: String,
    val requestFingerprint: String,
    val responseStatusCode: Int,
    val responseBody: Json?,
    val createdAt: OffsetDateTime? = null,

    @Transient
    var isNewRecord: Boolean = true
) : Persistable<String> {
    @PersistenceCreator
    constructor(
        idempotencyKey: String,
        principalId: String,
        operation: String,
        requestFingerprint: String,
        responseStatusCode: Int,
        responseBody: Json?,
        createdAt: OffsetDateTime?
    ) : this(
        idempotencyKey = idempotencyKey,
        principalId = principalId,
        operation = operation,
        requestFingerprint = requestFingerprint,
        responseStatusCode = responseStatusCode,
        responseBody = responseBody,
        createdAt = createdAt,
        isNewRecord = false
    )

    override fun getId(): String = idempotencyKey
    override fun isNew(): Boolean = isNewRecord
}
