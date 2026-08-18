-- Transactional outbox for the money-path events produced by Orders.
-- Rows are written in the SAME transaction as the order/idempotency change (see
-- CheckoutOrchestrator), then relayed to Kafka by Debezium's outbox EventRouter, which routes each
-- row to the topic named by its `type` column (order-placed / order-confirmed / notification-requested).
-- Mirrors the inventory service outbox (inventory V2__create_outbox.sql).
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
