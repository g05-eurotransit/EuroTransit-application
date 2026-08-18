-- api-design.md §Shared Request Headers: an idempotency key is scoped by caller/user context,
-- operation, and request fingerprint, so reusing the same key with a different logical payload
-- can be rejected with 409 Conflict (instead of silently replaying an unrelated result).
ALTER TABLE idempotency_keys ADD COLUMN principal_id VARCHAR(255);
ALTER TABLE idempotency_keys ADD COLUMN operation VARCHAR(100);
ALTER TABLE idempotency_keys ADD COLUMN request_fingerprint VARCHAR(255);
