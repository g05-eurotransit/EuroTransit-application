-- #140 adds a fifth reconciliation kind, PAYMENT_OUTCOME_UNKNOWN: an operator abandoned a PAYMENT_PENDING
-- order past its seat hold, and Payments holds no settled authorization for it. That does not prove
-- no money was taken: Payments calls the provider before writing its row, so a provider charge whose
-- answer was lost leaves no row. The order stays COMPENSATING until an operator settles the incident
-- with provider evidence (see ReconciliationKind.kt).
--
-- Same reason as V16: the CHECK constraint enumerates the kinds by name.
ALTER TABLE reconciliation_records DROP CONSTRAINT ck_reconciliation_kind;
ALTER TABLE reconciliation_records ADD CONSTRAINT ck_reconciliation_kind CHECK (
    kind IN (
        'DELIVERY_EXHAUSTED', 'INVARIANT_VIOLATION', 'PAYMENT_REVERSAL_FAILURE', 'SEAT_RETURN_FAILURE',
        'PAYMENT_OUTCOME_UNKNOWN'
    )
);
