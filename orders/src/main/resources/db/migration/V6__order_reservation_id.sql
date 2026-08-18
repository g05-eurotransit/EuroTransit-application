-- Persist the inventory reservation id on the order once seats are held, so a pre-payment
-- cancellation (POST /api/orders/{id}/cancel) can release the held seats. Nullable: only set after
-- the reserve step in the async pipeline; null for orders that never reached RESERVING.
ALTER TABLE orders ADD COLUMN reservation_id VARCHAR(255);
