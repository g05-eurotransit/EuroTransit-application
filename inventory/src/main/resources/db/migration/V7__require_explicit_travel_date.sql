-- V6 used CURRENT_DATE only to backfill pre-existing rows safely. New writes
-- must always provide the requested travel date explicitly.
ALTER TABLE seats
    ALTER COLUMN travel_date DROP DEFAULT;

ALTER TABLE reservations
    ALTER COLUMN travel_date DROP DEFAULT;
