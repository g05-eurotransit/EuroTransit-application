-- Generate seats for the next 30 days based on the current date seats
INSERT INTO seats (route_id, travel_date, seat_id, status)
SELECT 
    s.route_id, 
    d.travel_date, 
    s.seat_id, 
    'AVAILABLE'
FROM seats s
CROSS JOIN generate_series(
    CURRENT_DATE + interval '1 day', 
    CURRENT_DATE + interval '30 days', 
    interval '1 day'
) AS d(travel_date)
WHERE s.travel_date = CURRENT_DATE;
