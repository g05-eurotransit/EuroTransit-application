-- What the offer resolution produced at checkout: the currency the money is in, and the refund terms
-- the customer actually accepted.
--
-- V13 recorded `offer_id` as claimed by the client and said in as many words that no money decision
-- could be made on it. This migration is the other half: Orders now resolves the offer against
-- Catalog inside the synchronous checkout entry (issue #93), so the columns below hold *resolved*
-- values, not claimed ones.

-- The currency of `total_amount`.
--
-- Not new behaviour made configurable — a hardcode made visible. The authorization sent to Payments
-- read `currency = "EUR"` as a literal, so the amount and its unit came from two different places
-- and only one of them was ever checked. Latent today because all six seeded offers are EUR, and not
-- latent for long: two seeded routes are cross-border (Milano–Zürich, Torino–Lyon), and the first CHF
-- offer would have been authorized as the same *number* of euros.
--
-- NOT NULL: every order has a currency, and a nullable column would only move the question of what
-- null means to the payment call. The default backfills rows that predate the column and is then
-- dropped, exactly as V9 does for `travel_date`: keeping it would let a future insert that forgets
-- the currency silently record euros instead of failing. A checkout with no offer writes 'EUR'
-- explicitly, which is the old literal — now recorded as the assumption it is.
ALTER TABLE orders ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'EUR';
ALTER TABLE orders ALTER COLUMN currency DROP DEFAULT;

-- The refund rule, frozen at the moment of sale.
--
-- ADR-015 asked for the offer's `terms_version` so that a later catalogue edit could not rewrite what
-- a past customer accepted. A version marker only achieves that if the catalogue keeps its history,
-- and it does not: `InMemoryCatalogRepository.findOfferById` resolves against a flat list of current
-- offers, and no previous `termsVersion` is stored anywhere. So the *rule itself* is persisted here
-- and the version is kept beside it as an audit marker — same goal as ADR-015, by the only means that
-- actually reaches it.
--
-- Nullable, all three: only a checkout that carried an `offer_id` has resolved terms. An order
-- without them has no refund terms and the refund endpoint refuses it, which is the correct answer
-- for everything bought before this shipped. They become NOT NULL together with `offer_id` once the
-- SPA sends the field (issue #84).
ALTER TABLE orders ADD COLUMN refundable BOOLEAN;
ALTER TABLE orders ADD COLUMN refund_cutoff_minutes_before_departure INT;
ALTER TABLE orders ADD COLUMN offer_terms_version INT;

-- When this run departs, as an instant.
--
-- TIMESTAMPTZ for the same reason as `reservation_expires_at`, and it is the load-bearing detail:
-- Catalog holds `departure_time` as a zone-less local time plus the station's IANA zone, and Orders
-- holds `travel_date` as a date. A date plus a zone-less time is not a moment, and a refund cutoff
-- measured against a non-moment is not a rule — it is off by the offset, and off differently on each
-- side of a DST boundary.
--
-- Composed once here, at checkout, rather than on each refund. That keeps the zone arithmetic in one
-- place, keeps the refund path free of any dependency on Catalog being reachable, and means the
-- cutoff is evaluated by comparing one stored instant against the Orders database clock (ADR-015).
ALTER TABLE orders ADD COLUMN departure_at TIMESTAMPTZ;
