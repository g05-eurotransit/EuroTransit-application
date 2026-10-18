-- The refund's own bookkeeping (ADR-015, step 4). None of this decides eligibility: that is
-- `refundable`, `refund_cutoff_minutes_before_departure` and `departure_at` (V14), evaluated by the
-- refund endpoint against the Orders database clock at the moment of the CONFIRMED -> REFUNDING
-- transition. These three columns only record what happened once a refund was accepted.
--
-- All three nullable: every order that predates this step, and every order never refunded, has none
-- of them. `refunded_at` and `refund_provider_reference` stay null for the whole time an order sits
-- in REFUNDING -- that is the point of the state existing, rather than a REFUND_FAILED terminal.
ALTER TABLE orders ADD COLUMN refund_requested_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN refunded_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN refund_provider_reference VARCHAR(255);
