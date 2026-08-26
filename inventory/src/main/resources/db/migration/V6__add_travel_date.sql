-- Add travel_date to seats
ALTER TABLE seats 
ADD COLUMN travel_date DATE NOT NULL DEFAULT CURRENT_DATE;

-- Update the primary key for seats
ALTER TABLE seats DROP CONSTRAINT seats_pkey;
ALTER TABLE seats ADD PRIMARY KEY (route_id, travel_date, seat_id);

-- Add travel_date to reservations
ALTER TABLE reservations 
ADD COLUMN travel_date DATE NOT NULL DEFAULT CURRENT_DATE;
