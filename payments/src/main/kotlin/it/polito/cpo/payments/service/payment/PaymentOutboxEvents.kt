package it.polito.cpo.payments.service.payment

import io.r2dbc.postgresql.codec.Json
import it.polito.cpo.payments.model.OutboxEvent
import tools.jackson.databind.ObjectMapper

// Keyed by orderId, same as the old kafkaTemplate.send(eventType, orderId, ...) key.
internal fun paymentOutboxRow(
    objectMapper: ObjectMapper,
    aggregateType: String,
    orderId: String,
    type: String,
    event: Any,
): OutboxEvent = OutboxEvent(
    aggregateType = aggregateType,
    aggregateId = orderId,
    type = type,
    payload = Json.of(objectMapper.writeValueAsString(event)),
)
