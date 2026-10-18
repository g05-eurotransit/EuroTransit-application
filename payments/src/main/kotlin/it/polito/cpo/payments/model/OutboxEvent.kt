package it.polito.cpo.payments.model

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

// Relayed to Kafka by Debezium's EventRouter (routes by [type], keys by [aggregateId]).
@Table("outbox_events")
data class OutboxEvent(
    @Id @JvmField val id: UUID = UUID.randomUUID(),
    val aggregateType: String,
    val aggregateId: String,
    val type: String,
    val payload: Json,
    val createdAt: OffsetDateTime = OffsetDateTime.now()
) : Persistable<UUID> {
    override fun getId(): UUID = id

    // Pre-assigned id, always an INSERT.
    override fun isNew(): Boolean = true
}
