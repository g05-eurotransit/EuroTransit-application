-- ADR-015's post-sale refund adds a fourth reconciliation kind, SEAT_RETURN_FAILURE: the payment
-- reversal succeeded but Inventory definitively refused to return the seat, so the order stays
-- REFUNDING with an open incident rather than reaching a terminal state (see ReconciliationKind.kt).
--
-- V8's CHECK constraint enumerates the closed set by name, so a new kind needs a migration, not just
-- a new enum value -- an insert of 'SEAT_RETURN_FAILURE' would otherwise fail the constraint the
-- first time the seat return of a refund is refused, discovered only when it happens in a real
-- cluster rather than at compile time.
ALTER TABLE reconciliation_records DROP CONSTRAINT ck_reconciliation_kind;
ALTER TABLE reconciliation_records ADD CONSTRAINT ck_reconciliation_kind CHECK (
    kind IN ('DELIVERY_EXHAUSTED', 'INVARIANT_VIOLATION', 'PAYMENT_REVERSAL_FAILURE', 'SEAT_RETURN_FAILURE')
);
