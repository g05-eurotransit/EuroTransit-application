-- The customer-visible reason a checkout ended in FAILED.
--
-- Deliberately NOT the internal failure class. `reconciliation_records.failure_class` and the
-- `orders.checkout.failed` metric tag keep the full internal taxonomy for operators; this column
-- holds only the small closed set the customer is allowed to see, so that surfacing it through
-- GET /api/orders/{id} cannot leak an invariant violation or a provider code.
--
-- Nullable: it is set only on the terminal FAILED transition, and is null for every other state.
ALTER TABLE orders ADD COLUMN failure_code VARCHAR(32);
