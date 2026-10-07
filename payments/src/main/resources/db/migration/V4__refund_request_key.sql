-- The key the caller sent, kept for every attempt. Distinct from idempotency_key, which is the FK to
-- the settled record and is null for a decline (#111): without this a failed attempt is untraceable,
-- and the per-key count that varies the provider's idempotency key has nothing to count.
ALTER TABLE payment_refunds ADD COLUMN request_key VARCHAR(255);

UPDATE payment_refunds SET request_key = idempotency_key WHERE request_key IS NULL;

CREATE INDEX IF NOT EXISTS idx_payment_refunds_request_key_status
    ON payment_refunds (request_key, status);
