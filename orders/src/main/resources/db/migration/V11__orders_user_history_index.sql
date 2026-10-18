-- Supports the customer order history: GET /api/orders, scoped to one principal and paginated by
-- keyset on (created_at, id) descending.
--
-- Without it the endpoint is a sequential scan of the money-path table on every page, for every
-- customer. The id is part of the index because it is the pagination tie-breaker: created_at alone
-- is not unique, so two orders created in the same instant would make a page boundary skip or
-- repeat a row.
CREATE INDEX IF NOT EXISTS idx_orders_user_history
    ON orders (user_id, created_at DESC, id DESC);
