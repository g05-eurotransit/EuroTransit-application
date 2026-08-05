-- Enforce strict 1:1 relationship between an order and its inventory reservation
-- Prevents duplicate reservations when idempotency keys expire or are purged
ALTER TABLE reservations
    ADD CONSTRAINT uk_reservations_order_id UNIQUE (order_id);

