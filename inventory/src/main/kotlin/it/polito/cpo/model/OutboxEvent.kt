package it.polito.cpo.model

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

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
    
    // We treat every OutboxEvent as new because we only ever INSERT them.
    // This tells Spring Data R2DBC to use an INSERT statement instead of an UPDATE
    // even though the ID is pre-assigned.
    override fun isNew(): Boolean = true
}
