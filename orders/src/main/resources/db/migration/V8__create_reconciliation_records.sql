-- Typed reconciliation records for the Orders money path
-- (docs/design/reservation-confirmation.md, "Retry, Dead Letter, and Reconciliation").
--
-- Written by: the CONFIRMING_INVENTORY -> COMPENSATING transition (INVARIANT_VIOLATION), the payment
-- reversal path (PAYMENT_REVERSAL_FAILURE), and the order-placed.DLT consumer (DELIVERY_EXHAUSTED).
-- Never edited by an operator directly, only through the redrive/resolve endpoints.
--
-- No foreign key to orders(id) on purpose: a dead-letter record for an order id that does not exist
-- must not be able to break the DLT consumer. The consumer validates existence itself.
CREATE TABLE reconciliation_records (
    id                 UUID PRIMARY KEY,
    order_id           UUID         NOT NULL,
    kind               VARCHAR(32)  NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    -- Durable Orders state when the incident was recorded. It selects the resume action from the
    -- state table in the design doc. Kept as text so adding a state needs no data migration.
    order_state        VARCHAR(50)  NOT NULL,
    correlation_id     VARCHAR(255) NOT NULL,
    -- Low-cardinality failure class (see FailureClassifier); also used as a metric tag, so the set
    -- of values must stay closed.
    failure_class      VARCHAR(64)  NOT NULL,
    -- Human-readable latest failure. Never contains tokens, payment details, or personal data
    -- (docs/design/observability-conventions.md).
    failure_detail     VARCHAR(1024),
    -- How many times this open incident has recurred: DLT re-arrivals, repeated unknown reversals,
    -- operator redrives.
    attempt_count      INTEGER      NOT NULL DEFAULT 1,
    -- Operator-supplied incident reference, required to resolve an INVARIANT_VIOLATION.
    evidence_reference VARCHAR(255),
    -- 'system:terminal-state' for an automatic close, or the operator JWT subject.
    resolved_by        VARCHAR(255),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_reconciliation_kind CHECK (
        kind IN ('DELIVERY_EXHAUSTED', 'INVARIANT_VIOLATION', 'PAYMENT_REVERSAL_FAILURE')
    ),
    CONSTRAINT ck_reconciliation_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_reconciliation_resolution CHECK (
        (status = 'OPEN'     AND resolved_at IS NULL AND resolved_by IS NULL)
     OR (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT ck_reconciliation_attempts CHECK (attempt_count >= 1)
);

-- The natural key: at most ONE open incident per (order, kind). This is what makes "a repeated
-- dead letter updates the existing record instead of duplicating it" an ON CONFLICT target rather
-- than a read-then-write race between two consumer threads.
--
-- Deliberately partial rather than a plain UNIQUE (order_id, kind): an order that is redriven,
-- resolved, and later fails again must get a NEW incident row, not resurrect a closed one.
CREATE UNIQUE INDEX uk_reconciliation_open_order_kind
    ON reconciliation_records (order_id, kind)
    WHERE status = 'OPEN';

-- "All open incidents, oldest first" for the alerting gauge and the operator view.
CREATE INDEX ix_reconciliation_open_created_at
    ON reconciliation_records (created_at)
    WHERE status = 'OPEN';

-- Order-scoped lookups: the terminal-state auto-close and the redrive precondition check.
CREATE INDEX ix_reconciliation_order_id ON reconciliation_records (order_id);
