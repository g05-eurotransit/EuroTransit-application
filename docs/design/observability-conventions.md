# Observability Conventions

These conventions make every EuroTransit service observable the same way: one correlation id per request flow, structured JSON logs, RED metrics with histogram buckets, OTLP traces, and Kubernetes-ready health probes. Catalog is the reference implementation; the shared code lives in the `:observability` Gradle module.

## Adoption Checklist (3 actions per service)

1. Add the shared module to your service build file:

   ```kotlin
   dependencies {
       implementation(project(":observability"))
   }
   ```

   Because the module shares the `it.polito.cpo` root package, its components are picked up by component scan automatically. **This activates the correlation-id filter and the global error handler in your service** — your error responses switch to the shared `{code, message, correlationId}` model.

2. Copy the configuration block below into your `application.yaml`, changing nothing but what is service-specific (`spring.application.name` and `server.port` stay as you already have them):

   ```yaml
   spring:
     reactor:
       context-propagation: auto   # correlationId from Reactor context into logging MDC

   logging:
     structured:
       format:
         console: ecs              # JSON logs; includes MDC and trace/span ids

   management:
     endpoints:
       web:
         exposure:
           include: health,info,prometheus,metrics
     endpoint:
       health:
         probes:
           enabled: true           # /actuator/health/readiness and /liveness
     metrics:
       tags:
         application: ${spring.application.name}
       distribution:
         percentiles-histogram:
           http.server.requests: true   # buckets for p95/p99 SLO queries
     tracing:
       sampling:
         probability: 1.0
     opentelemetry:
       tracing:
         export:
           otlp:
             endpoint: http://localhost:4318/v1/traces
     otlp:
       metrics:
         export:
           enabled: false
   ```

   In tests, put overrides in `src/test/resources/application-test.yaml` (NOT `application.yaml`, which would shadow your main config on the test classpath) and annotate test classes with `@ActiveProfiles("test")`. See `catalog/src/test/resources/application-test.yaml`.

3. Verify (with `docker compose up -d prometheus grafana tempo` and your service running):

   ```bash
   curl -i localhost:<port>/<any-endpoint>                 # response carries X-Correlation-Id
   curl -i -H "X-Correlation-Id: demo-123" localhost:<port>/<any-endpoint>   # echoed back; visible in the JSON log line
   curl localhost:<port>/actuator/health/readiness         # {"status":"UP"}
   curl localhost:<port>/actuator/health/liveness          # {"status":"UP"}
   curl -s localhost:<port>/actuator/prometheus | grep http_server_requests
   ```

## Correlation ID Lifecycle

- Header: `X-Correlation-Id`. The gateway generates it for public inbound requests when missing; every service also accepts-or-generates (`CorrelationIdWebFilter`), so services are safe to call directly in local development.
- The filter stores the id on the response header, the exchange attribute `CorrelationId.EXCHANGE_ATTRIBUTE`, and the Reactor context key `correlationId`, which is bridged into the SLF4J MDC — every log line inside the request chain carries it automatically.
- Outgoing `WebClient` calls built from the auto-configured `WebClient.Builder` propagate `X-Correlation-Id` and add `X-Service-Name` (your `spring.application.name`) via `CorrelationWebClientCustomizer`. Do not construct `WebClient.create(...)` directly — inject the builder.
- Kafka events must carry `correlationId` in the event envelope (frozen contract in `docs/design/api-design.md`); the producing service copies it from the request context.

## Structured Logging

Boot-native ECS JSON on stdout. Example line (abridged):

```json
{"@timestamp":"2026-07-06T15:40:12.345Z","log.level":"INFO","message":"Request rejected: ROUTE_NOT_FOUND Route nope does not exist","correlationId":"demo-123","trace.id":"6f9a…","span.id":"12ab…","service.name":"catalog","log.logger":"it.polito.cpo.observability.GlobalErrorHandler"}
```

Must NOT be logged, ever: bearer tokens, `Authorization` header values, secrets, passwords, payment card data, provider API keys, full personal data. Money-path audit logging (correlation id, principal id, order id, outcome) respects the same exclusions; see [Money-Path Audit Log](#money-path-audit-log).

If JSON logs bother you during local development, disable them in your personal run configuration (`SPRING_APPLICATION_JSON` or a local profile) — do not remove the setting from `application.yaml`.

## Money-Path Audit Log

Orders, the money path's coordinator, writes one line per decision that moves or refuses money or a seat, to the logger named **`audit`** (`MoneyPathAudit`). Every other Orders log line is unchanged; the audit lines are added beside them, so the trail is one filter away: `log.logger = "audit"`.

```json
{"@timestamp":"2026-10-08T13:15:46.11Z","log":{"level":"INFO","logger":"audit"},"service":{"name":"orders"},"message":"audit checkout.pipeline confirmed","correlationId":"c2-proof-ok","action":"checkout.pipeline","outcome":"confirmed","principalId":"adf3224b-…","orderId":"f8477815-…"}
```

| Field | Meaning |
| --- | --- |
| `correlationId` | The request's correlation id (MDC). The pipeline lines carry the id the accept put on the event, so one id joins a checkout from the HTTP accept to the consumer's outcome. |
| `principalId` | Who the decision is attributed to: the caller (JWT `sub`) on an HTTP action, the order's owner on the asynchronous pipelines that act for them, the operator on a reconciliation action. |
| `orderId` | The order. `null` only for a checkout refused before an order existed. |
| `action`, `outcome` | Closed vocabulary, below. |
| `reason` | Only on `rejected`/`failed`: the API error code or the failure label (`FailureClassifier`), never free text. |
| `ownerId` | Only when the principal is not the owner: a staff cancel or refund, an operator redrive or resolve. |

| `action` | `outcome` |
| --- | --- |
| `checkout` | `accepted`, `replayed`, `rejected` |
| `checkout.pipeline` | `confirmed`, `failed` |
| `cancel` | `cancelled`, `rejected` |
| `refund` | `accepted`, `replayed`, `rejected` |
| `refund.pipeline` | `refunded`, `failed` (the order stays `REFUNDING`; an incident is open) |
| `reconciliation.redrive` | `accepted`, `replayed`, `rejected` |
| `reconciliation.resolve` | `resolved`, `replayed`, `rejected` |

What is deliberately **not** a line: a redelivery of an order already terminal (its outcome was written the first time), and a pipeline that finds the order cancelled under it (the `cancel` line already says so). `MoneyPathAudit.record` takes identifiers and closed-vocabulary values only — never an order, a request or an event — so a payment token, an email or an amount cannot reach the line by accident. `MoneyPathAuditTest` pins the shape, and `CheckoutOrchestratorTest` asserts that neither the payment token nor the email of an accepted checkout appears in its line.

## Metrics (RED)

- Source: Spring's built-in `http_server_requests_seconds_*` (count/sum/buckets), tagged with `application`, `uri`, `status`, `outcome`.
- Rate: `sum(rate(http_server_requests_seconds_count{application="catalog"}[5m]))`
- Errors: `sum(rate(http_server_requests_seconds_count{application="catalog", status=~"5.."}[5m]))`
- Duration p95: `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="orders", uri="/api/orders"}[5m])))`

These queries are the building blocks for the checkout SLOs in `docs/operations/slo-observability.md`. Custom business metrics (hold attempts, duplicate idempotency hits, circuit-breaker state) come later per that document; register them through the injected `MeterRegistry`.

## Tracing

- Export: OTLP HTTP to Tempo (`http://localhost:4318/v1/traces` locally; the cluster endpoint will be set per environment in the configuration repository).
- Property names are the Spring Boot 4 canonical ones (`management.opentelemetry.tracing.export.otlp.endpoint`); the Boot 3 `management.otlp.tracing.*` names are deprecated aliases.
- Sampling is 1.0 for development; production sampling is decided with the SLO owner before the demo.
- View traces in Grafana (`localhost:3000` → Explore → Tempo → search `service.name`).
- **Set `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` explicitly for every service in compose.** The
  `application.yaml` default is `localhost:4318`, which inside a container is the container itself,
  so the spans are produced and dropped and nothing reports an error. Catalog was missing from
  every trace for exactly this reason. A service that looks instrumented and exports nowhere is the
  failure mode to watch for here.
- **The gateway is part of the trace.** Traefik runs with `--tracing.otlp` to Tempo, so a request
  arriving from outside opens the root span and the service span is its child. Traefik injects
  `traceparent` into the forwarded request, which is what makes that parenting happen. Its endpoint
  argument is a base URL — the exporter appends `/v1/traces` itself — and the default is `https`,
  so it has to be spelled out.

## Prometheus Scraping and the Security Baseline

The shared security baseline permits `/actuator/prometheus` together with the Kubernetes health endpoints. Other Actuator endpoints remain denied by the `anyExchange().denyAll()` catch-all. The route is reachable by Prometheus on the internal service network, but no Traefik route forwards `/actuator/**` to an application service; backend routes match only `/api/**`. See `docs/operations/slo-observability.md` for the operational boundary.

## Open Cross-Slice Item: Notifications Consumer ↔ Orders Producer

The Notifications consumer (`notifications/src/main/kotlin/it/polito/cpo/event/`) consumes the `notification-requested` topic. Building it surfaced three items owned jointly with the Orders (Person 2) slice:

- **Event envelope drift — RESOLVED.** Previously Orders' event DTOs diverged from the frozen contract (`payloadVersion` vs `schemaVersion`, flat fields vs `payload {}` wrapper). Person 2 fixed this in PR #23: the shared `money-path-contracts` module (`money-path-contracts/src/main/kotlin/it/polito/cpo/contracts/events/OrderEvents.kt`) is now the single source of truth, aligned to `api-design.md` (`schemaVersion` + nested `payload`), and Orders emits it (ADR-010). The Notifications consumer now depends on the same module and deserializes the shared `NotificationRequestedEvent` directly — no more local DTO, no drift.
- **Checkout 500 — producer serializer missing JSR-310 module (BLOCKER, out of slice).** Found in live end-to-end run (2026-07-09): every first `POST /api/orders` returns 500. Root cause in Orders (Person 2): `orders/.../config/KafkaConfiguration.kt` builds the producer with a bare `JsonSerializer<Any>()` whose default Jackson mapper has no `JavaTimeModule`, so serializing `OrderPlacedEvent.occurredAt` (`LocalDateTime`) throws `InvalidDefinitionException: Java 8 date/time type not supported`. The order row persists but the event never publishes, so **no `notification-requested` is emitted and the customer never gets a notification**. A retry with the same `Idempotency-Key` replays the stored `ACCEPTED` response and masks the failure (no re-publish), which is why the bug is easy to miss. Fix is Person 2's: register `JavaTimeModule` (and disable `WRITE_DATES_AS_TIMESTAMPS`) on the producer's `JsonSerializer` mapper. Not changed here — out of slice.
- **Trace context across the outbox — RESOLVED for Orders (#117).** Business events do not go
  through `KafkaTemplate`; they are written to the outbox and relayed by Debezium, which had no trace
  context to forward, so one checkout produced eleven disconnected traces. Two fixes: the Orders
  listeners run the pipeline with the consumer's observation (`PipelineReactorContext`), and
  `OrderOutbox` writes each row under a short `PRODUCER` span `<event> publish` and stores its W3C
  `traceparent` in `outbox_events.traceparent`. The Orders connector copies that column into a
  `traceparent` record header (`transforms.outbox.table.fields.additional.placement`), which Spring
  Kafka's consumer observation reads. Locally a checkout is now one trace; the AKS connector still
  needs the same line. Inventory's and Payments' outboxes do not carry trace context yet — the same
  pattern applies. `correlationId` remains the cross-check in logs.
- **Kafka bootstrap address in compose.** Neither Orders nor Notifications set `spring.kafka.bootstrap-servers` in `docker-compose.yml`, so both fall back to `localhost:9092`, which does not resolve inside a container. Notifications now sets `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` for its own service block; Orders likely needs the same on its block (not changed here — out of slice).
- **Notifications not exposed through the gateway (in-slice, FIXED).** In live E2E the frontend SSE call `GET /api/notifications/stream` (via Traefik on port 80) fell through to the SPA catch-all router because the `notifications` service block had `traefik.enable=false` and no router. Added `traefik.enable=true` + a `PathPrefix(/api/notifications)` router to the notifications block in `docker-compose.yml` (mirrors catalog/orders). `inventory`/`payments` stay unexposed — they are internal, called server-to-server by Orders.
- **Topics rely on broker auto-create locally.** `order-placed`, `order-confirmed`, `notification-requested` are auto-created by the KRaft broker in compose (no explicit provisioning). The cluster/demo target needs explicit `KafkaTopic` CRDs (partitions/replication/retention) in `EuroTransit-configuration` (Strimzi) — a config-repo follow-up.

## Health Probes

- `management.endpoint.health.probes.enabled: true` exposes `/actuator/health/readiness` and `/actuator/health/liveness`; these are now configured in `application.yaml`, so the env vars in `k8s/smoke/eurotransit-smoke.yaml` are redundant (kept for now, harmless).
- Rule from the resilience plan: liveness must never depend on downstream services (no DB/Kafka checks in liveness); readiness may.

## Error Model

Throw `ApiException(status, code, message)` from `it.polito.cpo.observability` for business failures; `GlobalErrorHandler` renders the frozen error body and logs it with the correlation id. Codes are SCREAMING_SNAKE_CASE (`ROUTE_NOT_FOUND`, `IDEMPOTENCY_CONFLICT`, …) per `docs/design/api-design.md`.

## Local Observability Stack

`docker compose up -d prometheus grafana tempo`:

| Tool | URL | Purpose |
| --- | --- | --- |
| Prometheus | http://localhost:9090 | scrapes `/actuator/prometheus` on ports 8081–8085 every 5s |
| Grafana | http://localhost:3000 | anonymous admin; Prometheus + Tempo pre-provisioned |
| Tempo | http://localhost:3200 | OTLP trace ingest on 4318 |

Prometheus reaches services running on your host via `host.docker.internal` (works on macOS/Windows; on Linux it is mapped through `extra_hosts: host-gateway`).
