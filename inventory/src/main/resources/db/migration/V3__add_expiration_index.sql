-- Add an index to speed up the background job sweeping expired holds
CREATE INDEX IF NOT EXISTS idx_reservations_status_expires
    ON reservations(status, expires_at);
