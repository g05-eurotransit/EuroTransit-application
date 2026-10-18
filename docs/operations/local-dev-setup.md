# Local setup

`just compose-up` starts the local services and infrastructure and registers the Debezium connectors. The storefront uses Traefik at `http://localhost`; Keycloak runs at `http://localhost:8081`.

## Start and verify

From the application repository, with Docker/Compose, JDK 21 and `just` installed:

```bash
just compose-build
just compose-up
just compose-config
docker compose ps
```

The stack includes PostgreSQL, Kafka, Debezium Connect, Traefik, Keycloak and the configured Prometheus/Tempo/Grafana services. Inspect [docker-compose.yml](../../docker-compose.yml) for ports and enabled services. Build changed images before trusting a local reproduction; `up` can reuse an older image.

The frontend reads [public runtime settings](../../frontend/public/config/config.json), mounted read-only into nginx. Browser-readable configuration contains no secrets. Follow [local authentication](../design/local-auth.md) to obtain a test principal.

## Outbox diagnostics

If an order remains `ACCEPTED`, inspect the relay before changing business state:

```bash
./scripts/init-local-debezium.sh
curl -fsS http://localhost:8083/connectors
```

The initialization script waits for all three outbox tables, registers each connector with idempotent `PUT`, and checks its status. Each task must be running. An `order-placed` payload must be a flat JSON object.

| Symptom | Check |
| --- | --- |
| Payload is a quoted JSON string | `transforms.outbox.table.expand.json.payload` must be enabled |
| Payload has a `schema`/`payload` wrapper | Compose must use `CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE=false` |
| Task cannot find `outbox_events` | Verify Flyway completed before connector registration |
| Task is running but accepted work does not advance | Inspect outbox/WAL progress, Kafka delivery and Orders logs; use the [recovery runbook](orders-recovery-runbook.md) for incidents |

The [cluster connector lifecycle](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/debezium-connector-lifecycle.md) owns Kubernetes ordering and recovery.

## Stop

```bash
just compose-down
```

Do not add `-v` for normal shutdown: it deletes local database volumes. Database reset is a separate operation with its own data-loss consequences.
