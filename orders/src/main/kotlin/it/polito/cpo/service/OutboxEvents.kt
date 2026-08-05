package it.polito.cpo.service

import io.r2dbc.postgresql.codec.Json
import it.polito.cpo.model.OutboxEvent
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Builds an outbox row for an order-scoped event.
 *
 * Shared rather than duplicated per caller because Debezium's EventRouter reads these column values
 * directly: it routes by [type] to the topic of the same name and keys by `aggregate_id`. A caller
 * that drifted on either would silently stop being relayed, with no error anywhere.
 */
internal fun orderOutboxRow(
    objectMapper: ObjectMapper,
    type: String,
    orderId: UUID,
    event: Any,
): OutboxEvent = OutboxEvent(
    aggregateType = "Order",
    aggregateId = orderId.toString(),
    type = type,
    payload = Json.of(objectMapper.writeValueAsString(event)),
)
