# Observability Conventions

These conventions make every EuroTransit service observable the same way: one correlation id per request flow, structured JSON logs, RED metrics with histogram buckets, OTLP traces, and Kubernetes-ready health probes. Catalog is the reference implementation; the shared code lives in the `:observability` Gradle module.

## Service adoption

1. Add the shared module to your service build file:

   ```kotlin
   dependencies {
       implementation(project(":observability"))
   }
   ```

   Because the module shares the `it.polito.cpo` root package, its components are picked up by component scan automatically. This activates the correlation-id filter and global error handler: your error responses switch to the shared `{code, message, correlationId}` model.

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
- The filter stores the id on the response header, the exchange attribute `CorrelationId.EXCHANGE_ATTRIBUTE`, and the Reactor context key `correlationId`, which is bridged into the SLF4J MDC : every log line inside the request chain carries it automatically.
- Outgoing `WebClient` calls built from the auto-configured `WebClient.Builder` propagate `X-Correlation-Id` and add `X-Service-Name` (your `spring.application.name`) via `CorrelationWebClientCustomizer`. Do not construct `WebClient.create(...)` directly : inject the builder.
- Kafka events must carry `correlationId` in the event envelope (frozen contract in `docs/design/api-design.md`); the producing service copies it from the request context.

## Structured Logging

Boot-native ECS JSON on stdout. Example line (abridged):

```json
{"@timestamp":"2026-07-06T15:40:12.345Z","log.level":"INFO","message":"Request rejected: ROUTE_NOT_FOUND Route nope does not exist","correlationId":"demo-123","trace.id":"6f9a…","span.id":"12ab…","service.name":"catalog","log.logger":"it.polito.cpo.observability.GlobalErrorHandler"}
```

Must NOT be logged, ever: bearer tokens, `Authorization` header values, secrets, passwords, payment card data, provider API keys, full personal data. Money-path audit logging (correlation id, principal id, order id, outcome) respects the same exclusions; see [Money-Path Audit Log](#money-path-audit-log).

If JSON logs bother you during local development, disable them in your personal run configuration (`SPRING_APPLICATION_JSON` or a local profile) : do not remove the setting from `application.yaml`.

## Money-Path Audit Log

Orders, the money path's coordinator, writes one line per decision that moves or refuses money or a seat, to the logger named **`audit`** (`MoneyPathAudit`). Every other Orders log line is unchanged; the audit lines are added beside them, so the trail is one filter away: `log.logger = "audit"`.

```json
{"@timestamp":"2026-10-08T13:15:46.11Z","log":{"level":"INFO","logger":"audit"},"service":{"name":"orders"},"message":"audit checkout.pipeline confirmed","correlationId":"c2-proof-ok","action":"checkout.pipeline","outcome":"confirmed","principalId":"adf3224b-…","orderId":"f8477815-…"}
```

| Field | Meaning |
| --- | --- |
| `correlationId` | The request's correlation id (MDC). The pipeline lines carry the id the accept put on the event, so one id joins a checkout from the HTTP accept to the consumer's outcome. |
| `principalId` | Who the decision is attributed to: the caller (JWT `sub`) on an HTTP action, the order's owner on the asynchronous pipelines that act for them, the operator on a reconciliation action, and `system:hold-expiry` for the T1 expiry job (ADR-016), which nobody asked for. |
| `orderId` | The order. `null` only for a checkout refused before an order existed. |
| `action`, `outcome` | Closed vocabulary, below. |
| `reason` | Only on `rejected`/`failed`: the API error code or the failure label (`FailureClassifier`), never free text. |
| `ownerId` | Only when the principal is not the owner: a staff cancel or refund, an operator redrive or resolve, a hold closed by the expiry job. |

| `action` | `outcome` |
| --- | --- |
| `checkout` | `accepted`, `replayed`, `rejected` |
| `checkout.pipeline` | `confirmed`, `failed` |
| `payment.submit` | `accepted`, `replayed`, `rejected` (ADR-016; `reason` is `HOLD_EXPIRED`, `PAYMENT_ALREADY_STARTED`, `ORDER_NOT_PAYABLE` or `IDEMPOTENCY_CONFLICT`) |
| `hold.expiry` | `cancelled` (ADR-016: a customer hold closed at T1) |
| `cancel` | `cancelled`, `rejected` |
| `refund` | `accepted`, `replayed`, `rejected` |
| `refund.pipeline` | `refunded`, `failed` (the order stays `REFUNDING`; an incident is open) |
| `reconciliation.redrive` | `accepted`, `replayed`, `rejected` |
| `reconciliation.resolve` | `resolved`, `replayed`, `rejected` |
| `reconciliation.abandon` | `accepted`, `replayed`, `rejected` |
| `reconciliation.settle` | `resolved`, `replayed`, `rejected` |

What is deliberately **not** a line: a redelivery of an order already terminal (its outcome was written the first time), and a pipeline that finds the order cancelled under it (the `cancel` line already says so). `MoneyPathAudit.record` takes identifiers and closed-vocabulary values only : never an order, a request or an event : so a payment token, an email or an amount cannot reach the line by accident. `MoneyPathAuditTest` pins the shape, and `CheckoutOrchestratorTest` asserts that neither the payment token nor the email of an accepted checkout appears in its line.

## Metrics (RED)

- Source: Spring's built-in `http_server_requests_seconds_*` (count/sum/buckets), tagged with `application`, `uri`, `status`, `outcome`.
- Rate: `sum(rate(http_server_requests_seconds_count{application="catalog"}[5m]))`
- Errors: `sum(rate(http_server_requests_seconds_count{application="catalog", status=~"5.."}[5m]))`
- Duration p95: `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="orders", uri="/api/orders"}[5m])))`

HTTP RED queries measure HTTP handling. Checkout outcomes and accepted-payment-to-terminal latency use the business metrics defined in [SLOs and SLIs](../design/slo.md); do not substitute acceptance or polling duration for completion. Register bounded business metrics through the injected `MeterRegistry`.

## Tracing

- Export: OTLP HTTP to Tempo (`http://localhost:4318/v1/traces` locally; the cluster endpoint will be set per environment in the configuration repository).
- Property names are the Spring Boot 4 canonical ones (`management.opentelemetry.tracing.export.otlp.endpoint`); the Boot 3 `management.otlp.tracing.*` names are deprecated aliases.
- Sampling is 1.0 for development; production sampling is decided with the SLO owner before the demo.
- View local traces through the [local telemetry tools](local.md#local-observability-stack).
- **Set `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` explicitly for every service in compose.** The
  `application.yaml` default is `localhost:4318`, which inside a container is the container itself,
  so the spans are produced and dropped and nothing reports an error. Catalog was missing from
  every trace for exactly this reason. A service that looks instrumented and exports nowhere is the
  failure mode to watch for here.
- **The gateway is part of the trace.** Traefik runs with `--tracing.otlp` to Tempo, so a request
  arriving from outside opens the root span and the service span is its child. Traefik injects
  `traceparent` into the forwarded request, which is what makes that parenting happen. Its endpoint
  argument is a base URL : the exporter appends `/v1/traces` itself : and the default is `https`,
  so it has to be spelled out.

## Prometheus Scraping and the Security Baseline

The shared security baseline permits `/actuator/prometheus` together with the Kubernetes health endpoints. Other Actuator endpoints remain denied by the `anyExchange().denyAll()` catch-all. The route is reachable by Prometheus on the internal service network, but no Traefik route forwards `/actuator/**` to an application service; backend routes match only `/api/**`. See `docs/design/slo.md` for the operational boundary.

## Event and trace propagation

Shared DTOs in [money-path-contracts](../../money-path-contracts/src/main/kotlin/it/polito/cpo/contracts/events/OrderEvents.kt) define the event envelope; the [API contract](../design/api-design.md) owns producers and consumers. Business publication uses transactional outboxes, not a direct Kafka send after the database commit.

Orders' `OrderOutbox` creates a short producer span and stores W3C `traceparent` on each outbox row. The Orders connector maps it to a Kafka header, and listeners resume the consumer observation through `PipelineReactorContext`. Inventory and Payments outboxes still lack this trace-context path. A configured connector header mapping does not establish an exported cluster trace; see [cluster monitoring](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/observability.md).

## Health Probes

- `management.endpoint.health.probes.enabled: true` exposes `/actuator/health/readiness` and `/actuator/health/liveness`; these are now configured in `application.yaml`, so the env vars in `k8s/smoke/eurotransit-smoke.yaml` are redundant (kept for now, harmless).
- Rule from the resilience plan: liveness must never depend on downstream services (no DB/Kafka checks in liveness); readiness may.

## Error Model

Throw `ApiException(status, code, message)` from `it.polito.cpo.observability` for business failures; `GlobalErrorHandler` renders the frozen error body and logs it with the correlation id. Codes are SCREAMING_SNAKE_CASE (`ROUTE_NOT_FOUND`, `IDEMPOTENCY_CONFLICT`, …) per `docs/design/api-design.md`.

