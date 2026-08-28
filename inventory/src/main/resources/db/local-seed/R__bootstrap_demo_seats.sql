-- Local-only demo capacity. Cluster environments bootstrap the same data with
-- a Kubernetes Job, keeping demo data outside the versioned Flyway chain.
WITH demo_routes(route_id) AS (
    VALUES
        ('rt-to-mi-001'),
        ('rt-mi-pa-002'),
        ('rt-mi-zu-003'),
        ('rt-ro-vi-004'),
        ('rt-to-ly-005')
),
demo_seats(seat_id) AS (
    SELECT row_number::text || column_name
    FROM generate_series(1, 6) AS rows(row_number)
    CROSS JOIN (VALUES ('A'), ('B'), ('C'), ('D')) AS columns(column_name)
),
demo_dates(travel_date) AS (
    SELECT generated_at::date
    FROM generate_series(
        CURRENT_DATE,
        (CURRENT_DATE + INTERVAL '6 months')::date,
        INTERVAL '1 day'
    ) AS dates(generated_at)
)
INSERT INTO seats (route_id, travel_date, seat_id, status)
SELECT route_id, travel_date, seat_id, 'AVAILABLE'
FROM demo_routes
CROSS JOIN demo_dates
CROSS JOIN demo_seats
ON CONFLICT (route_id, travel_date, seat_id) DO NOTHING;
