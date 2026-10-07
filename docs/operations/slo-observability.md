# SLOs, SLIs, Dashboards, and Alerts

The critical user journey is checkout: client request through gateway, Orders, Inventory, Payments, Kafka event publication, and final order confirmation.

## Checkout SLOs

| SLO | Target | SLI | Measurement |
| --- | --- | --- | --- |
| Checkout success rate | 99.0% over 30 days | Successful confirmed checkouts / valid checkout attempts | Orders emits terminal status metrics by result. |
| Checkout latency | 95% of successful checkouts complete within 2 seconds over 30 days | Duration from `POST /api/orders` accepted to confirmed/failed terminal status | Distributed trace span and Orders histogram. |
| Checkout acceptance latency | 99% of `POST /api/orders` responses return within 300 ms | HTTP server duration at gateway and Orders | Gateway and Orders RED metrics. |
| Notification degradation | 99.9% of successful checkout requests must not fail because Notifications is unavailable | Checkout failures attributed to Notifications / checkout attempts | Orders failure reason metric. |

## Error Budget

- Checkout success-rate error budget: 1.0% failed valid checkout attempts per 30 days.
- Burn-rate alerts must page on user-visible symptoms, not CPU usage alone.
- A release is paused or rolled back if the Orders canary consumes more than 10% of the daily error budget during the canary window.

## Dashboards

- **Money Path RED:** request rate, error rate, and duration for gateway, Orders, Inventory, and Payments.
- **Kafka Pipeline:** consumer lag, produced/consumed event counts, duplicate events, dead-letter count.
- **Inventory Correctness:** hold attempts, successful holds, rejected holds, expired holds, duplicate idempotency hits, oversell invariant violations.
- **Payment Safety:** authorization attempts, duplicate idempotency hits, declines, retry count, circuit-breaker state.
- **Infrastructure USE/Golden Signals:** CPU, memory, network, disk, pod restarts, saturation, and dependency health.

## Alerts

| Alert | Trigger | Why it matters |
| --- | --- | --- |
| CheckoutFastBurn | Checkout success-rate SLO burns fast for 10 minutes | Users cannot complete purchases. |
| CheckoutSlowBurn | Checkout success-rate SLO burns slowly for 1 hour | Persistent degradation needs investigation. |
| CheckoutLatencyHigh | p95 checkout latency above 2 seconds for 15 minutes | Critical path is slower than the stated SLO. |
| PaymentCircuitOpen | Orders payment circuit breaker remains open for 5 minutes | Payment dependency is failing or overloaded. |
| InventoryReservationFailures | Reservation failure ratio spikes without matching sold-out signals | Possible inventory consistency or database issue. |
| KafkaPipelineStalled | Order pipeline consumer lag grows for 15 minutes | Async confirmation/notification is not converging. |

### Reservation confirmation and reconciliation

Proposed for the configuration repository, which owns the `PrometheusRule` objects.
These cover the money-path states introduced by the synchronous Inventory confirmation
(`docs/design/reservation-confirmation.md`).

| Alert | Expression sketch | Why it matters |
| --- | --- | --- |
| ReconciliationRecordOpen | `max(orders_reconciliation_open) > 0` for 5m | An incident automatic recovery could not resolve is waiting for an operator. A counter cannot express this: a record open for a week must keep alerting. |
| PaymentReversalFailure | `increase(orders_reconciliation_required_total{kind="PAYMENT_REVERSAL_FAILURE"}[10m]) > 0` | Money was taken and a reversal definitively failed; the order is deliberately non-terminal. |
| InvariantViolation | `increase(orders_reconciliation_required_total{kind="INVARIANT_VIOLATION"}[10m]) > 0` | Inventory reported a state the model says cannot occur. |
| CheckoutStuckNonTerminal | `max(orders_pipeline_oldest_age_seconds) > checkout recovery SLO` | An order is stranded in `PAYMENT_PENDING`, `CONFIRMING_INVENTORY` or `COMPENSATING`. |
| InventorySeatConflict | `increase(orders_inventory_confirm_total{code="RESERVATION_SEAT_CONFLICT"}[10m]) > 0` | Seat ownership disagreement between the two services. |
| ReconciliationAgeHigh | `max(orders_reconciliation_oldest_age_seconds) > incident response SLO` | An incident has been waiting too long, whatever its kind. |
| PoisonOrderEvent | `increase(orders_events_dlt_total{result="poison"}[10m]) > 0` | A record reached `order-placed-dlt` with a payload that cannot be parsed, so no incident could be attached to an order. |
| DeadLetterUnknownOrder | `increase(orders_events_dlt_total{result="unknown_order"}[10m]) > 0` | A dead letter referenced an order id that does not exist: producer or data-retention problem. |

**Blocker:** none of these can fire yet. The Orders security chain authorizes only the
health endpoints and the order APIs, then denies everything else, so
`/actuator/prometheus` is not scrapable. Opening it is a Gateway/Security decision and
is tracked separately.

## Tracing

Every checkout trace must include:

- Gateway inbound request span.
- Orders acceptance span.
- Inventory hold/reservation span.
- Payments authorization span.
- Kafka publish spans for order/payment/notification events.
- Consumer spans for asynchronous stages.

The required presentation question is: "Where did this order spend its time, and where did it fail?"

### Where this stands

Measured on the compose stack, not assumed.

**Emitted today.** The gateway span exists: Traefik runs with OTLP tracing to Tempo and injects
`traceparent` into the forwarded request, so the service span is its child rather than a root of
its own. A checkout renders as one tree from Traefik through Orders to Catalog — the offer lookup
included.

Catalog reached Tempo only once its exporter endpoint was set in compose. It had been falling back
to the `application.yaml` default of `localhost:4318`, which inside a container is the container
itself: spans were produced and dropped with no error. The service was absent from every trace.
The other four already set it.

**One checkout, one trace (2026-10-07, #117).** The two breaks are fixed. Inside the Orders
listeners the pipeline now runs with the consumer's observation (break A), and every outbox row
carries the `traceparent` of an `<event> publish` span that the Orders connector places in a record
header (break B), so each consumer is a child of the span that wrote its event. Measured on compose,
one checkout to `CONFIRMED` is a single trace of 36 spans across Traefik and the five services it
touches:

```
traefik POST → orders POST /api/orders
  → catalog GET /api/catalog/offers/{offerId}
  → order-placed publish → order-placed process
       → inventory POST /api/inventory/reservations        (hold)
       → payments  POST /api/payments/authorize             (authorize)
       → inventory PUT  …/reservations/{id}/confirmation    (confirm)
       → order-confirmed publish
       → notification-requested publish → notifications notification-requested process
```

A refund is likewise one trace: `POST /api/orders/{orderId}/refund` → `refund-requested publish` →
`refund-requested process` → Payments refund → Inventory seat return. A row written with tracing off
relays a null header; its consumer starts a new trace and nothing fails.

Two shapes worth knowing before reading one in Tempo. A redelivered record — a Kafka retry or a
duplicate relay — is another `process` span under the same `publish` span, so retries are visible
as siblings. A **redrive** writes a new row from the operator's request, so the resumed pipeline
lives in the redrive's trace, not the original checkout's; `correlationId` links the two.

**Still missing in the cluster.** The AKS Orders connector
(`EuroTransit-configuration/platform/connectors/base/orders-outbox-connect.yaml`) does not yet place
the header, so there the consumer still starts its own trace. The line is the same as in
`scripts/init-local-debezium.sh`; it waits for the AKS maintenance window because the Connect worker
is shared.

**Not available in the cluster at all.** `platform/` has no trace backend, and the application
chart ships `tracing.enabled: false` with an endpoint pointing at a collector that does not exist.
The evidence above can only be produced locally.
