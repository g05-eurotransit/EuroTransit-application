-- Table for seats availability and physical resource state
CREATE TABLE seats (
    route_id VARCHAR(100) NOT NULL,
    seat_id VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE, HELD, SOLD
    reservation_id VARCHAR(100),
    PRIMARY KEY (route_id, seat_id)
);

-- Table for tracking the 10-minute hold logic
CREATE TABLE reservations (
    reservation_id VARCHAR(100) PRIMARY KEY,
    order_id UUID NOT NULL,
    route_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL, -- HELD, CONFIRMED
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Table for enforcing exactly-once execution (30 minutes retention)
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(100) NOT NULL,
    principal_id VARCHAR(100) NOT NULL,
    operation VARCHAR(50) NOT NULL,
    request_fingerprint VARCHAR(255) NOT NULL,
    response_status_code INT NOT NULL,
    response_body JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (idempotency_key, principal_id)
);
