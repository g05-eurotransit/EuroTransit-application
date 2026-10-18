-- ADR-016: when the customer pays, a customer-hold reservation gets one T2 payment window, opened
-- by PUT /api/inventory/reservations/{id}/payment-window. This records that it was opened, so a
-- replay returns the stored deadline instead of extending it a second time (invariant 9).
--
-- Nullable with no default: every existing reservation never had a window, and a direct-flow
-- reservation never gets one.
ALTER TABLE reservations
    ADD COLUMN payment_window_started_at TIMESTAMP WITH TIME ZONE;
