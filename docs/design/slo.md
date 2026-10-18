# Checkout SLOs and SLIs

These targets define the customer money path. A successful HTTP acceptance is not a confirmed checkout; an HTTP request histogram is not asynchronous completion latency. Configuration maps these definitions to deployable dashboards and alerts in its [observability reference](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/observability.md).

## Checkout SLOs

| SLO | Target | SLI | Measurement |
| --- | --- | --- | --- |
| Checkout success rate | 99.0% over 30 days | Successful confirmed checkouts / valid checkout attempts | Orders emits terminal status metrics by result. |
| Checkout latency | 95% of successful checkouts complete within 2 seconds over 30 days | Duration from the accepted payment to confirmed/failed terminal status: `POST /api/orders/{orderId}/payment` for a customer-hold order (`payment_submitted_at`), `POST /api/orders` for a direct-flow order | `orders_checkout_e2e_duration_seconds` (same name and buckets as before) and distributed traces. |
| Checkout acceptance latency | 99% of `POST /api/orders` and `POST /api/orders/{orderId}/payment` responses return within 300 ms | HTTP server duration at gateway and Orders | Gateway and Orders RED metrics. |
| Notification degradation | 99.9% of successful checkout requests must not fail because Notifications is unavailable | Checkout failures attributed to Notifications / checkout attempts | Orders failure reason metric. |

**Why the latency SLI starts at the payment (ADR-016).** In the customer-hold flow the order is
created when the customer presses Continue, and the seats are then held for up to ten minutes (T1)
while the customer fills in the card. Measuring from `POST /api/orders` would count that reading time
as checkout latency and break the 2-second target on every order. The metric name is unchanged; dashboard and alert queries still need to select the actual end-to-end histogram. The
per-delivery timer `orders_checkout_duration_seconds` gains the value `held_awaiting_payment` on its
`outcome` tag for the delivery that takes a hold and parks.

## Error Budget

- Checkout success-rate error budget: 1.0% failed valid checkout attempts per 30 days.
- Burn-rate alerts must page on user-visible symptoms, not CPU usage alone.
- A release is paused or rolled back if the Orders canary consumes more than 10% of the daily error budget during the canary window.

## Recovery Objectives

These recovery objectives are proposed and await team confirmation. They are not an adopted failover RTO. The policy and the operator steps are in
[orders-recovery-runbook.md](../operations/orders-recovery-runbook.md).

| Objective | Target | Measured by |
| --- | --- | --- |
| Automatic convergence | An accepted order caught by a dependency outage of up to about a minute reaches a terminal state without an operator, within about a minute of the dependency returning. | `orders_pipeline_oldest_age_seconds`; no `orders_events_exhausted_total{failure_class="dependency_error"}` increase. |
| Checkout recovery SLO (proposed `CheckoutStuckNonTerminal` mapping) | 3 minutes: the retry budget (1.5 to about 2.3 minutes in real time) plus the pipeline itself, with margin. | `max(orders_pipeline_oldest_age_seconds) > 180` |
| Incident response SLO (proposed `ReconciliationAgeHigh` mapping) | An open incident is redriven or resolved within 5 minutes, and a `PAYMENT_PENDING` one always before its order's `reservationExpiresAt`; past it, that redrive is refused. That deadline is ten minutes from the hold in the direct flow, and in the customer-hold flow ten minutes from the payment (T2) once the payment window opened, or still T1 if it never did. | `max(orders_reconciliation_oldest_age_seconds) > 300` |
| Hold expiry (ADR-016) | A customer-hold order nobody paid for is cancelled within about 15 seconds of its T1 deadline, by the Orders job; Inventory's own sweep frees the seat at the same deadline regardless. | `orders_holds_overdue`, `orders_holds_overdue_oldest_seconds` |

An outage longer than the budget opens its incident 1.5 to about 2.3 minutes after the payment
started : the hold in the direct flow, the payment submission in the customer-hold flow, since T2
restarts there : so the operator has about 7.5 to 8.5 minutes, of which `ReconciliationRecordOpen`'s
`for: 5m` currently spends five. The one exception is a customer-hold order whose payment window never
opened because Inventory was down at the payment: its deadline is still T1, which can be close.
A shorter `for` is proposed to the configuration repository.

## Measurement and tracing

Use terminal outcome counters and the end-to-end histogram for completed checkout signals; report acceptance duration separately. Define the valid-attempt population before claiming success-rate compliance, including treatment of expected business refusals, pending work and ingress rejection. No short baseline or fault experiment establishes a rolling 30-day SLO.

A checkout trace should connect public ingress, Orders acceptance, Inventory hold/payment window/confirmation, payment provider work and Kafka publication/consumption. [Instrumentation conventions](../development/instrumentation.md) own metric names, correlation and trace propagation. Trace IDs in logs do not establish that a queryable trace backend exists.

The [cluster evidence index](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/verification/README.md) records measured recovery, while [readiness](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/readiness.md) records outstanding SLI mapping and tracing gaps. Recovery observations remain useful when no prior numeric RTO was adopted; do not infer compliance or invent a retrospective target.
