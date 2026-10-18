# Runtime conventions

Services use environment-based configuration, framework health endpoints and immutable release identities. Deployment-specific names and secret provisioning belong to the [configuration reference](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/deployment.md).

## Configuration inputs

| Concern | Inputs and behavior |
| --- | --- |
| HTTP | Cluster containers use port 8080; standalone defaults can differ. Internal calls use service DNS. Public routing belongs to Traefik. |
| Profiles | Kubernetes sets `SPRING_PROFILES_ACTIVE=cluster`; inspect Compose for local overrides. The `dev` profile can select local substitutes and is not cluster proof. |
| PostgreSQL | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` compose both R2DBC runtime and JDBC/Flyway startup URLs |
| Kafka | `SPRING_KAFKA_BOOTSTRAP_SERVERS`, as read by each service's application configuration |
| OIDC | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` must describe the same issuer even when network URLs differ |
| Orders service identity | `ORDERS_SERVICE_TOKEN_URI`, `ORDERS_SERVICE_CLIENT_ID`, `ORDERS_SERVICE_CLIENT_SECRET` |

Each money-path service owns its schema migrations; applied Flyway versions are never edited or reordered. Demo seed belongs outside versioned schema migrations. A development volume containing retired seed migrations may need an explicitly destructive reset; never apply that procedure to shared business data.

## Health and lifecycle

[Observability conventions](instrumentation.md#health-probes) own probe semantics. Readiness uses the configured availability state; do not assume it tests database or Kafka connectivity. A healthy process and a usable checkout path are separate observations. [Shutdown](../design/runtime-lifecycle.md) describes drain and replay; configuration owns Kubernetes grace/preStop budgets.

## Secrets and images

Use managed environment injection for provider and database credentials. Cluster ciphertext and controller-key lifecycle belong to [security and secrets](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/security-and-secrets.md). Local development values must not be presented as production credentials. Publish immutable image identities; a mutable local alias does not supply release provenance.
