-- The day the customer is travelling, not the day they bought.
--
-- Issue #64: a seat used to be identified by (route_id, seat_id) alone, so selling seat 1A on a
-- route sold it forever, for every future day. Inventory now keys seats by
-- (route_id, travel_date, seat_id); an order has to carry the same dimension, both to build the
-- reservation request and to record what was actually purchased.
--
-- The default backfills the rows that predate this column and is then dropped: keeping it would let
-- a future insert that forgets the date silently record a purchase for today instead of failing.
ALTER TABLE orders ADD COLUMN travel_date DATE NOT NULL DEFAULT CURRENT_DATE;
ALTER TABLE orders ALTER COLUMN travel_date DROP DEFAULT;
