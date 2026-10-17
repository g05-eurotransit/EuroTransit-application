-- ADR-016: the customer seat hold with two timers.
--
-- payment_deferred: which flow the order follows, recorded durably so a redelivery resumes the right
-- one. TRUE for a checkout placed without a payment token (the customer pays later, while T1 runs);
-- FALSE for the direct flow, which carries the token and runs straight through. Every existing order
-- is a direct-flow order, which is exactly what the default says.
ALTER TABLE orders ADD COLUMN payment_deferred BOOLEAN NOT NULL DEFAULT FALSE;

-- payment_submitted_at: when the customer's payment was accepted, by the Orders database clock. The
-- checkout latency SLO and the non-terminal age gauges start here, so the minutes a customer spends on
-- the checkout page are never counted as pipeline time. Null for a direct-flow order, whose payment
-- arrived with the order itself: those measurements fall back to created_at.
ALTER TABLE orders ADD COLUMN payment_submitted_at TIMESTAMPTZ;

-- payment_window_opened: Inventory opened the one-time T2 window and Orders recorded its deadline in
-- reservation_expires_at, in the same statement, before Payments was ever called. It decides what a
-- redelivery of PAYMENT_PENDING does: without it, a refused window can fail the order as unpaid;
-- with it, an authorization may already have been attempted and the redelivery must go straight to
-- authorizing (reservation-confirmation.md, invariant 8).
ALTER TABLE orders ADD COLUMN payment_window_opened BOOLEAN NOT NULL DEFAULT FALSE;

-- One active customer hold per customer (invariant 11), enforced here rather than by a read-then-
-- insert in the service, which two concurrent requests would both pass. Direct-flow orders are not
-- counted: the load test and the canary jobs place many of them in parallel for one test user.
-- No existing row can violate it, because no existing row has payment_deferred = TRUE.
CREATE UNIQUE INDEX uk_orders_one_active_hold_per_user
    ON orders (user_id)
    WHERE payment_deferred AND status IN ('ACCEPTED', 'RESERVING', 'HELD_AWAITING_PAYMENT');

-- The T1 expiry job's scan.
CREATE INDEX idx_orders_held_awaiting_payment_expiry
    ON orders (reservation_expires_at)
    WHERE status = 'HELD_AWAITING_PAYMENT';
