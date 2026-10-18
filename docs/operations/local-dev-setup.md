# Local Development Environment Setup

This document describes how to start and configure the local infrastructure required to develop and run the EuroTransit applications locally.

## Infrastructure Stack

The `docker-compose.yml` file in the root of the project provides the entire stack needed:
- PostgreSQL (with logical replication enabled)
- Apache Kafka (KRaft mode)
- Kafka Connect / Debezium (for the Transactional Outbox Pattern)
- Traefik API Gateway
- Keycloak (IAM)
- Observability Stack (Prometheus, Tempo, Grafana)

To start the infrastructure:
```bash
docker compose up -d
```

The frontend reads its public, environment-specific settings at runtime from
`frontend/public/config/config.json`. Docker Compose mounts this file read-only into nginx;
the production image deliberately contains no fallback configuration. Keep secrets out of
this file because browsers can retrieve it directly.

## Configuring the Transactional Outbox Pattern (Debezium)

EuroTransit relies on the Transactional Outbox Pattern to provide a durable outbox / at-least-once delivery without distributed transactions. Locally, we use Debezium running in Kafka Connect to read the `outbox_events` table and forward events to Kafka.

`just compose-up` registers the connectors for you. Run the script by hand only if you brought the
stack up with a bare `docker compose up -d`.

**If a checkout stops in `ACCEPTED` and the order page polls forever, this is where to look.** The
outbox row is written in the same transaction as the order, so the order always exists; everything
after it depends on Connect relaying that row. A connector that is missing, or configured
differently from the cluster, breaks the relay without producing an error anyone sees: the customer
sees a spinner, and the only trace is `orders.events.consumed{result="poison"}` plus a
`DELIVERY_EXHAUSTED` reconciliation record.

Two settings carry that risk, and both are silent when wrong:

- **`transforms.outbox.table.expand.json.payload`** — `payload` is `JSONB`, so the EventRouter
  extracts it as a string. Without expansion the converter serializes that string again and the
  topic carries `"{\"eventId\": ...}"`, a JSON string rather than an object.
- **`CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE`** in `docker-compose.yml` — the `CONNECT_` prefix is
  required. The Debezium entrypoint maps a fixed set of bare names and passes anything else through
  only when prefixed; unprefixed, the setting is dropped and every value is wrapped in
  `{"schema":..., "payload":...}`.

Either one alone is enough to stop the money path. **Keep both in sync with the cluster connectors**
in `EuroTransit-configuration` (`platform/connectors/base/{orders,inventory}-outbox-connect.yaml`):
a local stack that routes the outbox differently from production is not a rehearsal for production.

### 1. Wait for Debezium to be ready
Ensure that the `debezium` container is running and healthy on port 8083.

### 2. Run the Initialization Script
Execute the provided initialization script from the root of the project:

```bash
./scripts/init-local-debezium.sh
```

### 3. Verify Configuration
The script uses `PUT` on each connector's config, so it is idempotent: a first run answers `201
Created` and a re-run answers `200 OK`, updating the existing connector rather than failing. It
checks the status itself and exits non-zero on anything else.

To verify the relay end to end rather than just the registration, place an order and check that it
leaves `ACCEPTED`. A message on `order-placed` should be a flat JSON object — no `schema` wrapper,
no quoted string.
Debezium is now listening to `outbox_events` and automatically routing payloads (like `inventory-reserved` or `inventory-failed`) to their respective Kafka topics.
