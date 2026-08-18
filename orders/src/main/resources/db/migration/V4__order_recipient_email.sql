-- Delivery metadata (NOT an ownership key, per api-design.md §Security Boundary): captured from the
-- JWT `email` claim at checkout so the asynchronous pipeline (which runs in the order-placed consumer,
-- with no request/JWT in scope) can address the confirmation/failure notification.
ALTER TABLE orders ADD COLUMN recipient_email VARCHAR(320);
