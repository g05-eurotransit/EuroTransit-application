CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    operation VARCHAR(100) NOT NULL,
    principal_id VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(255) NOT NULL,
    response_code INT NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment_authorizations (
    id UUID PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL,
    principal_id VARCHAR(100) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(50) NOT NULL,
    provider_reference VARCHAR(255),
    error_code VARCHAR(255),
    idempotency_key VARCHAR(255) REFERENCES idempotency_records(idempotency_key),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment_refunds (
    id UUID PRIMARY KEY,
    authorization_id UUID NOT NULL,
    amount DECIMAL(10, 2),
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(50) NOT NULL,
    provider_reference VARCHAR(255),
    idempotency_key VARCHAR(255) REFERENCES idempotency_records(idempotency_key),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
