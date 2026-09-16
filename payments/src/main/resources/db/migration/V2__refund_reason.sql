-- A checkout compensation versus a commercial refund; both share the "payment-refunded" event.
-- Default seeds existing rows, then it is dropped so a future insert must set the column explicitly.
ALTER TABLE payment_refunds ADD COLUMN reason VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN';

-- Backfill from the idempotency-key namespace that already separates the two (ADR-015).
UPDATE payment_refunds
SET reason = CASE
    WHEN idempotency_key LIKE 'pay-refund-%' THEN 'COMMERCIAL'
    WHEN idempotency_key LIKE 'refund-%' THEN 'COMPENSATION'
    ELSE 'UNKNOWN'
END;

ALTER TABLE payment_refunds ALTER COLUMN reason DROP DEFAULT;
