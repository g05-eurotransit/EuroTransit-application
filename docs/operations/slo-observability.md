# SLOs, SLIs, Dashboards, and Alerts

The operational load harness, scenarios, fixtures, correctness verifier, runner
and execution reports are owned by the
[configuration repository](https://github.com/g05-eurotransit/EuroTransit-configuration/tree/feat/resilience-verification/load-tests).
Application changes for that harness (#135) expose the missing end-to-end histogram and
metrics for the actual circuit-breaker, bulkhead and retry registries. Those changes do not
change admission, pools, timeouts or retry behavior; the retry budget before a dead letter is
described under Recovery Objectives below.

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

## Recovery Objectives

Proposed by the Orders owner on 2026-10-13, after the AKS experiments (configuration #55); to be
confirmed by the team. The policy and the operator steps are in
[orders-recovery-runbook.md](orders-recovery-runbook.md).

| Objective | Target | Measured by |
| --- | --- | --- |
| Automatic convergence | An accepted order caught by a dependency outage of up to about a minute reaches a terminal state without an operator, within about a minute of the dependency returning. | `orders_pipeline_oldest_age_seconds`; no `orders_events_exhausted_total{failure_class="dependency_error"}` increase. |
| Checkout recovery SLO (the `CheckoutStuckNonTerminal` threshold below) | 3 minutes: the retry budget (1.5 to about 2.3 minutes in real time) plus the pipeline itself, with margin. | `max(orders_pipeline_oldest_age_seconds) > 180` |
| Incident response SLO (the `ReconciliationAgeHigh` threshold below) | An open incident is redriven or resolved within 5 minutes, and a `PAYMENT_PENDING` one always before its order's `reservationExpiresAt` (the ten-minute seat hold); past it, that redrive is refused. | `max(orders_reconciliation_oldest_age_seconds) > 300` |

An outage longer than the budget opens its incident 1.5 to about 2.3 minutes after the hold was taken,
so the operator has about 7.5 to 8.5 minutes, of which `ReconciliationRecordOpen`'s `for: 5m` currently
spends five.
A shorter `for` is proposed to the configuration repository.

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
| CheckoutStuckNonTerminal | `max(orders_pipeline_oldest_age_seconds) > 180` (checkout recovery SLO, above) | An order is stranded in `PAYMENT_PENDING`, `CONFIRMING_INVENTORY` or `COMPENSATING`. |
| InventorySeatConflict | `increase(orders_inventory_confirm_total{code="RESERVATION_SEAT_CONFLICT"}[10m]) > 0` | Seat ownership disagreement between the two services. |
| ReconciliationAgeHigh | `max(orders_reconciliation_oldest_age_seconds) > 300` (incident response SLO, above) | An incident has been waiting too long, whatever its kind. |
| PoisonOrderEvent | `increase(orders_events_dlt_total{result="poison"}[10m]) > 0` | A record reached `order-placed-dlt` with a payload that cannot be parsed, so no incident could be attached to an order. |
| DeadLetterUnknownOrder | `increase(orders_events_dlt_total{result="unknown_order"}[10m]) > 0` | A dead letter referenced an order id that does not exist: producer or data-retention problem. |

**Two dead-letter counters, counted on different pods.** `orders_events_dlt_total{topic,result}` is
incremented by the dead-letter *consumers*, which run in their own group (`orders-dlt`), so it lands on
whichever pod reads the dead letter: right for the two alerts above, which do not care which pod.
`orders_events_exhausted_total{topic,failure_class}` is incremented by the pod that **gave up** on the
delivery, once the dead letter is published, labelled with the `failure_class` an incident gets when
one is opened (a poison payload, an unknown order or an already-terminal order opens none). That is
the one to filter by pod — the canary gate `canary-dead-letter-events` belongs on it, not on
`orders_events_dlt_total` (step 5, F4). Every `topic` × `failure_class` series is registered at zero
when Orders starts, so `increase()` sees the first dead letter on a new pod rather than a series that
appears already at 1. The checkout outcomes the canary also gates on are registered the same way:
`orders_checkout_completed_total` for `result="confirmed"` and `"failed"`, and
`orders_checkout_failed_total` for every `reason` a failed checkout can carry.

**A dead letter is never dropped on its way to becoming an incident.** The dead-letter consumers
retry recording the `DELIVERY_EXHAUSTED` incident with an unbounded exponential back-off (1s
doubling to 30s), and treat every exception as retryable: a failure that ends — a database outage —
delays the incident instead of losing it. Until 2026-10-08 they gave up after three attempts in
about two seconds, logged, and committed the record — the incident was lost and the order stuck with
no signal. The price is that a *permanent* failure (a bug, or data that cannot be stored) now stalls
that DLT stream on the pod: each listener's single consumer thread owns all of its topic's
partitions there, so every later dead letter on that topic waits behind the failing one. Each failed
attempt counts as `orders_events_dlt_total{result="record_failed"}`, registered at zero per DLT
topic, and that is the signal. Suggested alert for the configuration repository, sized to ignore a
single transient blip:

| Alert | Expression sketch | Why it matters |
| --- | --- | --- |
| DeadLetterNotRecorded | `increase(orders_events_dlt_total{result="record_failed"}[10m]) > 3`, ticket | A dead letter keeps failing to become an incident, and the other dead letters on that topic wait behind it until someone acts. |

**Not yet for the alerts.** Five alerts above still read counters created by their first event, so
the first such event on a pod is invisible to `increase()`: PaymentReversalFailure and
InvariantViolation (`orders_reconciliation_required_total`), InventorySeatConflict
(`orders_inventory_confirm_total`), PoisonOrderEvent and DeadLetterUnknownOrder
(`orders_events_dlt_total`). For rare events the first is usually the only one. Until those series are
registered at zero too, a reversal failure or invariant violation still surfaces through
`ReconciliationRecordOpen` and `ReconciliationAgeHigh` (gauges, unaffected), and a seat conflict
through the invariant record it opens; a poison record or a dead letter for an unknown order opens no
incident, so for those there is no other signal.

`/actuator/prometheus` is scrapable: the shared security baseline
(`EuroTransitSecurity.UNAUTHENTICATED_ACTUATOR_ENDPOINTS`) permits it, and no Traefik route exposes
it outside the service network. *(Until that change these alerts could not fire; the note saying so
is removed.)*

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

**On the cluster since configuration #58.** The AKS Orders connector
(`EuroTransit-configuration/platform/connectors/base/orders-outbox-connect.yaml`) places the header
with the same line as `scripts/init-local-debezium.sh`. Verified on 2026-10-16: the `order-placed`,
`order-confirmed` and `notification-requested` records of one checkout (`CONFIRMED`) carry
`traceparent` with the same trace id, so the Kafka hop no longer starts a new trace.

**Not available in the cluster at all.** `platform/` has no trace backend, and the application
chart ships `tracing.enabled: false` with an endpoint pointing at a collector that does not exist.
The evidence above can only be produced locally.
