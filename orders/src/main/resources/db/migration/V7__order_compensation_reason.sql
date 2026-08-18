-- Durable failure class for the COMPENSATING phase.
--
-- A Kafka redelivery that resumes a crashed checkout enters COMPENSATING directly and no longer
-- holds the Inventory refusal in memory. Without this column it cannot know (a) which
-- reconciliation record kind applies, or (b) that on RESERVATION_ORDER_CONFLICT the reservation
-- belongs to a different order and must never be released.
--
-- Nullable: written only by the CONFIRMING_INVENTORY -> COMPENSATING transition.
ALTER TABLE orders ADD COLUMN compensation_reason VARCHAR(64);
