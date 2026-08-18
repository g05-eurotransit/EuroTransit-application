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

## Metrics Scraping and Network Boundary

Every service permits unauthenticated access to `/actuator/prometheus` and the health probe endpoints so that Prometheus and Kubernetes can reach them without customer OAuth credentials. All other Actuator endpoints remain denied by the shared security baseline.

This does not make service metrics public through the marketplace gateway. No Traefik route forwards `/actuator/**` to an application service: backend routes match `/api/catalog`, `/api/orders`, or `/api/notifications`, while the low-priority host-only fallback targets the frontend. Prometheus scrapes each service endpoint from inside the cluster network.

## Tracing

Every checkout trace must include:

- Gateway inbound request span.
- Orders acceptance span.
- Inventory hold/reservation span.
- Payments authorization span.
- Kafka publish spans for order/payment/notification events.
- Consumer spans for asynchronous stages.

The required presentation question is: "Where did this order spend its time, and where did it fail?"
