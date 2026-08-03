-- Add an index to speed up the background retention job purging stale idempotency records (> 30 min)
CREATE INDEX IF NOT EXISTS idx_idempotency_created_at
    ON idempotency_records(created_at);

