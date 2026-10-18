# Local development

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

The frontend reads [public runtime settings](../../frontend/public/config/config.json), mounted read-only into nginx. Browser-readable configuration contains no secrets. Follow [local authentication](local.md) to obtain a test principal.

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
| Task is running but accepted work does not advance | Inspect outbox/WAL progress, Kafka delivery and Orders logs; use the [recovery runbook](../operations/orders-recovery-runbook.md) for incidents |

The [cluster connector lifecycle](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/cdc.md) owns Kubernetes ordering and recovery.

## Identity

Keycloak starts with the Compose stack and is available at:
- **Admin Console**: `http://localhost:8081` (Username: `admin`, Password: `admin`)
- **OIDC Issuer URL**: `http://localhost:8081/realms/eurotransit`

The `eurotransit` realm is automatically imported on startup.

The `eurotransit-frontend` client explicitly maps `preferred_username` and
`email` into its ID token and UserInfo response, matching the Kubernetes realm
in the [cluster realm](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/platform/keycloak/eurotransit-realm.yaml).
The frontend requests `openid` and uses these claims for the signed-in name and
avatar. A new login is required to receive changed ID token claims.

## Available Test Users

| Username | Password | Role | Purpose |
|----------|----------|------|---------|
| `alice`  | `alice`  | `customer` | Use this user to test checkout and order placement. |
| `admin`  | `admin`  | `operations` | Use this user to test operational tasks and reading all orders. |

The local realm also defines the `service` role for internal service-to-service calls. Customer tokens must not be accepted by Inventory or Payments internal APIs.

## Local tokens

To call protected endpoints on the Gateway or Services during local development, you need a Bearer token.

### Command line

You can get a token for the user `alice` using the `eurotransit-frontend` public client:

```bash
export TOKEN=$(just customer-token)
```

The command prints only the access token on success. On failure, it prints the HTTP status and the Keycloak error payload so local checkout tests do not fail with an opaque empty token or `NO_TOKEN`.

Override the local helper defaults when needed:

```bash
KEYCLOAK_USERNAME=admin KEYCLOAK_PASSWORD=admin just customer-token
```

### Using Postman / Insomnia

Create a new OAuth 2.0 authorization helper:
- **Grant Type**: Password Credentials
- **Access Token URL**: `http://localhost:8081/realms/eurotransit/protocol/openid-connect/token`
- **Client ID**: `eurotransit-frontend`
- **Username**: `alice`
- **Password**: `alice`

## Issuer and signing-key access

In your local `application-local.yml`, configure your OAuth2 resource server to use the Keycloak issuer:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8081/realms/eurotransit
          jwk-set-uri: http://localhost:8081/realms/eurotransit/protocol/openid-connect/certs
```

When services run inside Docker Compose, the token issuer remains `http://localhost:8081/realms/eurotransit` because tokens are obtained from the host. The services retrieve signing keys through the Docker network using:

```yaml
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: http://localhost:8081/realms/eurotransit
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: http://keycloak:8080/realms/eurotransit/protocol/openid-connect/certs
```

This keeps issuer validation strict while avoiding container-to-host name resolution problems for JWKS discovery.

## Troubleshooting Local Tokens

Keycloak imports the realm on startup with the development import strategy. If a local Keycloak container already has a realm in its internal state, starting the container again may keep the existing realm instead of replacing it with the JSON file under `infrastructure/local/keycloak`.

If `just customer-token` fails:

- `Realm does not exist`: the `eurotransit` realm was not imported or the request is reaching a different Keycloak instance. Recreate the local Keycloak container with `docker compose rm -sf keycloak && docker compose up -d keycloak`, then confirm the import mount points at `infrastructure/local/keycloak/eurotransit-realm.json`.
- `unauthorized_client`: confirm the `eurotransit-frontend` client exists in the `eurotransit` realm and has direct access grants enabled.
- `invalid_grant`: confirm the user credentials, that the user is enabled, and that the password is not temporary.
- `NO_TOKEN`, empty output, or `null`: rerun `just customer-token` directly and use the printed Keycloak error payload as the source of truth.

## Authorization reference

The [API security matrix](../design/api-design.md#security-boundary) owns roles and ownership rules. Local issuer/JWKS configuration must agree with tokens obtained from Keycloak.

## Stop

```bash
just compose-down
```

Do not add `-v` for normal shutdown: it deletes local database volumes. Database reset is a separate operation with its own data-loss consequences.

## Local Observability Stack

`docker compose up -d prometheus grafana tempo`:

| Tool | URL | Purpose |
| --- | --- | --- |
| Prometheus | http://localhost:9090 | scrapes `/actuator/prometheus` on ports 8081 to 8085 every 5s |
| Grafana | http://localhost:3000 | anonymous admin; Prometheus + Tempo pre-provisioned |
| Tempo | http://localhost:3200 | OTLP trace ingest on 4318 |

Prometheus reaches services running on your host via `host.docker.internal` (works on macOS/Windows; on Linux it is mapped through `extra_hosts: host-gateway`).

### Scrape Labels and Dashboard Compatibility

Local Grafana dashboards (provisioned from `docker/observability/grafana/provisioning/dashboards/`) query Prometheus metrics using cluster-aligned selectors such as `{namespace="eurotransit", app="<service>"}`.

To make these dashboards work locally without divergent PromQL queries, `docker/observability/prometheus.yml` statically decorates every local scrape target (both container `:8080` and host `8081-8085`) with `namespace: eurotransit` and `app: <service>`, mirroring the labels attached by Kubernetes `ServiceMonitor` resources in the cluster.

> [!WARNING]
> Do not strip or rename `namespace` or `app` labels in `docker/observability/prometheus.yml`. Removing them will silently break panel queries across all local Grafana dashboards, resulting in `No Data`.
