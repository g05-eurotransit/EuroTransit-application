-- The checkout pipeline now runs asynchronously in the `order-placed` Kafka consumer, which
-- must be able to reconstruct the payment authorization request from the persisted order.
-- The payment method token is a sandbox reference (not card data); it is cleared once the order
-- reaches a terminal state (CONFIRMED/FAILED) for hygiene.
ALTER TABLE orders ADD COLUMN payment_method_token VARCHAR(255);
