package it.polito.cpo.model

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Transactional-outbox row. Written in the same transaction as the order change; relayed to Kafka by
 * Debezium's EventRouter, which routes by the [type] column to the matching topic and keys by
 * [aggregateId]. Mirrors the inventory service's OutboxEvent.
 */
@Table("outbox_events")
data class OutboxEvent(
    @Id @JvmField val id: UUID = UUID.randomUUID(),
    val aggregateType: String,
    val aggregateId: String,
    val type: String,
    val payload: Json,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    // W3C trace context of the span that wrote the row; Debezium relays it as the `traceparent`
    // record header so the consumer joins the writer's trace. Null when tracing is off.
    val traceparent: String? = null,
) : Persistable<UUID> {
    override fun getId(): UUID = id

    // Every OutboxEvent is only ever INSERTed; tell Spring Data R2DBC to INSERT even though the
    // id is pre-assigned.
    override fun isNew(): Boolean = true
}
