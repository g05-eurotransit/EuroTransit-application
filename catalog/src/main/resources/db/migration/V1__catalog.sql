CREATE TABLE routes (
    route_id VARCHAR(255) PRIMARY KEY,
    display_order INT NOT NULL UNIQUE CHECK (display_order > 0),
    origin TEXT NOT NULL,
    destination TEXT NOT NULL,
    departure_time TIME NOT NULL,
    arrival_time TIME NOT NULL,
    departure_zone TEXT NOT NULL,
    train_type TEXT NOT NULL,
    operating_days VARCHAR[] NOT NULL,
    CHECK (cardinality(operating_days) BETWEEN 1 AND 7),
    CHECK (operating_days <@ ARRAY['MON','TUE','WED','THU','FRI','SAT','SUN']::VARCHAR[])
);

CREATE TABLE offers (
    offer_id VARCHAR(255) PRIMARY KEY,
    route_id VARCHAR(255) NOT NULL REFERENCES routes(route_id),
    fare_class VARCHAR(50) NOT NULL,
    price NUMERIC(19,2) NOT NULL CHECK (price > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    refundable BOOLEAN NOT NULL,
    refund_cutoff_minutes_before_departure INT CHECK (refund_cutoff_minutes_before_departure >= 0),
    terms_version INT NOT NULL CHECK (terms_version > 0),
    display_note TEXT,
    CHECK (refundable OR refund_cutoff_minutes_before_departure IS NULL)
);
CREATE INDEX offers_route_id_idx ON offers(route_id);
