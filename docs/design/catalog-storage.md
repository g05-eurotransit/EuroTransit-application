# Catalog storage

Catalog supports PostgreSQL persistence behind the existing read APIs. Select it explicitly with `CATALOG_STORAGE=postgres`; the default remains `in-memory` for compatibility with environments that have not provisioned the database. A PostgreSQL failure never switches the service to static offers.

## Ownership and data

Catalog owns `catalog_db` and its dedicated database credentials. The `routes` and `offers` tables retain the public identifiers, route ordering, decimal prices, currency, schedules, IANA departure zones, operating days and refund terms. Flyway creates the schema and inserts the initial five routes and six offers once. Repeated startup does not reset stored data.

There are no offer-management APIs in this change. Runtime and migration connections use the dedicated Catalog owner role, following the current service convention. Separate migration and read-only runtime roles remain a future hardening step. Never expose the database credential through the public API or logs.

## Configuration and failure behavior

PostgreSQL mode requires `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` and `DB_PASSWORD`; the database name defaults to `catalog_db`. JDBC is used by Flyway during startup; request-time reads use R2DBC. The default pool has at most five connections, three-second acquisition and creation limits, and a validation query. Database health participates in readiness and does not participate in liveness.

The HTTP contracts, public anonymous Catalog reads and protected Orders APIs remain unchanged. Prices and structured refund policies are authoritative; `conditions` is display text. Orders verifies the offer before accepting a checkout, so an unavailable Catalog prevents new purchases. Existing Orders records retain their accepted refund policy, currency and departure instant; refunds do not depend on Catalog being reachable.

## Rollout and compatibility

The database is independent of Orders migrations. Preserve all seeded IDs and values when enabling it. The unsupported exchange promise is absent from the Standard Milano–Paris offer in both implementations.

Returning to the previous image and configuration is safe for this initial unchanged catalog. Once administrators can edit commercial terms, returning to static data could restore obsolete prices or conditions and must be reassessed. Offer history and management are outside this change.

The [Kind procedure](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/operations/catalog-postgres-kind.md) owns provisioning, preview, fault injection and rollback. [PostgresCatalogApiTests](../../catalog/src/test/kotlin/it/polito/cpo/catalog/PostgresCatalogApiTests.kt) compares both stores and exercises the same API contract against PostgreSQL.
