# Orders: Shutdown, Failure Domain and Replay

Orders stops new admission, finishes the current Kafka record and leaves unstarted work for redelivery. The mechanism below is supported by code and Compose drills; see
[the shutdown report](reports/orders-shutdown-local.md). Kubernetes observations are recorded separately in the [cluster report](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/reliability/kind-orders-shutdown.md).

## Where work runs

| Work | Runs on | Bounded by |
| --- | --- | --- |
| HTTP: checkout accept, cancel, refund request, reconciliation operations | Reactor Netty, suspending controllers | Catalog client budget; one acceptance transaction |
| `order-placed` → checkout pipeline | the `orders` consumer thread, `runBlocking` (`OrderPlacedEventListener.kt`) | [Checkout retry policy](../design/reservation-confirmation.md#retry-and-reconciliation) |
| `refund-requested` → refund pipeline | its own consumer thread, `runBlocking` (`RefundRequestedEventListener.kt`) | same |
| `order-placed-dlt`, `refund-requested-dlt` → incidents | their own consumer threads, group `orders-dlt` | unbounded back-off until recorded (`KafkaConfiguration.kt`) |
| `@Scheduled` jobs (gauges, reconciliation metrics, idempotency cleanup, hold expiry) | the scheduler thread, `runBlocking` | bounded reads, idempotent cleanup or state compare-and-set |

**The failure domain is one record.** There is no detached coroutine scope any more (ADR-011 removed
it): each pipeline runs to completion inside the listener call that received its record, so an
exception in one checkout ends that call, goes to the error handler for that record, and touches
nothing else. Nothing is cancelled on shutdown either : the record in progress finishes; the ones
not yet started are left to Kafka.

## The stop sequence

On SIGTERM Spring closes the context in this order:

1. **Readiness flips to `OUT_OF_SERVICE`** at once (`ContextClosedEvent`;
   `management.endpoint.health.group.readiness` includes only `readinessState`,
   `application.yaml:84`). Liveness stays `UP` : it never depends on downstream.
2. **Kafka listener containers stop** (phase `Integer.MAX_VALUE - 100`, the highest, so first). With
   `spring.kafka.listener.immediate-stop: true` (`application.yaml:28`, and read by the dead-letter
   factory, `KafkaConfiguration.kt:226`) each container finishes **the record in progress** and stops.
   Without it : spring-kafka's default : it would first process the rest of the batch it had already
   polled, which with a slow dependency can outlast everything below.
3. **The web server drains** (`server.shutdown: graceful`, `application.yaml:63`): no new
   connections; requests in flight complete.
4. The rest of the context (connection pools, schedulers) closes.

Each phase may take up to `spring.lifecycle.timeout-per-shutdown-phase: 25s` (`application.yaml:6`).
Measured on compose, from SIGTERM to the container exiting: 3.3 to 3.8s with checkouts in flight,
5.5s with Payments unreachable (the in-flight authorization attempt ran out its timeout first), and
7.9s on a backlog before `immediate-stop` : every case far inside the 35s Kubernetes leaves.

| Environment | Budget after SIGTERM | Where set |
| --- | --- | --- |
| Kubernetes | 45s grace − 10s preStop sleep = **35s** | configuration `charts/eurotransit/values.yaml:20-21` |
| docker compose | **10s** (`docker stop` default; no `stop_grace_period`) | : use `docker compose stop -t 35` to mirror Kubernetes |

The preStop sleep is what keeps Kubernetes from routing to a pod that is already refusing: endpoints
are removed while the pod still serves. Compose has no equivalent : Traefik keeps the route until the
container is gone, and then falls through to the frontend's catch-all (`405` on `POST /api/orders`,
`200` HTML on a GET), which is a compose artefact, not Orders' answer.

## Offsets and replay

- **Ack mode is the default, BATCH**: the offsets of the records a container processed are committed
  after the batch, and on stop for the records it finished. A record that was polled but not started,
  or not finished, is not committed : it is redelivered to the partition's next owner (this pod after
  a restart, another replica during a rollout).
- **Redelivery is safe because the pipelines dispatch on durable state.**
  `CheckoutOrchestrator.processOrderPlaced` (`:437`) reads the order and resumes from the phase it is
  in : a terminal order is skipped, `PAYMENT_PENDING` re-authorizes under the same idempotency key
  (`pay-{orderId}`) and Payments replays its stored answer. `RefundOrchestrator.processRefundRequested`
  (`:216`) resumes only `REFUNDING`. Inventory refuses a second reservation for an order
  (`uk_reservations_order_id`).
- **A stop interrupts retry backoff without recovering the record**: the sleep checks `isRunning` (`ListenerUtils.stoppableSleep`), the
  record is not dead-lettered and not committed ("Record in retry and not yet recovered"), and it is
  redelivered. Shutdown therefore never turns a transient failure into a dead letter. The in-memory
  retry count restarts with the pod, so a record can get a few extra attempts : harmless.
- **The dead-letter listeners** retry until the incident is written and never give up (#129); a stop
  interrupts that back-off the same way.

## Scheduled jobs

Gauges and reconciliation metrics are recomputed on the next run; idempotency cleanup repeats its bounded delete. The customer-hold expiry job also resumes after restart, with Inventory retaining authority over seat expiry.

## Notifications cannot fail a checkout

Orders makes no call to Notifications. `notification-requested` is a row written to `outbox_events` in
the same transaction that commits `CONFIRMED` or `FAILED`, and Debezium relays it (ADR-011). With
Notifications down the event waits in Kafka and the checkout completes; when it returns, its consumer
(group `notifications`) resumes from its committed offset (ADR-004). Proven in
[the Notifications outage report](reports/notifications-outage-local.md).

What Notifications keeps is its own: its read model is in memory, so the history `GET
/api/notifications` returns starts again at every restart : see the report.
