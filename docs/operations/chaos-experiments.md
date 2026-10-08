# Chaos Experiment Plan

Each experiment must be run as a hypothesis-driven test. Reports should keep the same structure: steady state, hypothesis, injected failure, observations, conclusion, and follow-up change.

## Experiment 1: Payments Latency Injection

- **Steady state:** Checkout success rate is within SLO, p95 checkout latency is below 2 seconds, Catalog requests are healthy.
- **Hypothesis:** If Payments latency increases, Orders opens the payment circuit breaker, returns controlled checkout failures or queued/degraded status, and Catalog remains unaffected.
- **Injection:** Add latency to Payments traffic using Chaos Mesh or equivalent.
- **Observe:** Orders circuit-breaker state, checkout latency/error rate, Catalog RED metrics, traces showing Payments delay.
- **Success condition:** No unbounded hangs, no retry storm, Catalog remains inside SLO.

## Experiment 2: Inventory Pod Kill Mid-Hold

- **Steady state:** Hold success/failure/expiration metrics are stable and no oversell invariant violations are reported.
- **Hypothesis:** Killing Inventory during hold creation does not oversell seats and does not double-charge payments because idempotency records and atomic hold transitions are used.
- **Injection:** Kill an Inventory pod during concurrent checkout load.
- **Observe:** Duplicate idempotency hits, hold state transitions, expired holds, order terminal states, payment authorization count per order. Also, and specifically: whether any order is left in `RESERVING` — the phase an order is in while its hold request is unanswered, which is exactly what a kill mid-hold produces — and whether a `reconciliation_records` row was opened for it, with which `kind` and `failure_class`. `RESERVING` is deliberately not in the pipeline gauges, so watch it through the incident instead: `orders_reconciliation_open{kind="DELIVERY_EXHAUSTED"}` and the `ReconciliationRecordOpen` alert, then read the record's `order_state` from the database (there is no API that lists incidents). Orders caught by the kill during a later confirmation will sit in `CONFIRMING_INVENTORY` instead, which is gauged; count them separately.
- **Success condition:** Each order has at most one active hold result and at most one payment authorization result.
- **The property this experiment is really testing (invariant 8).** An unknown outcome must never become a business decision. Killing Inventory mid-hold produces exactly that: Orders cannot tell a lost request from a refused one. The correct behaviour is that the durable phase is preserved and the delivery is retried or dead-lettered — **never** a speculative decision. No money has moved yet at `RESERVING`, so the risk is not a refund but a hold released or an order failed on a guess. An order that ends `FAILED` quickly is a worse result here than one that sits in `RESERVING` with an open incident, and the report must not read the first as success.

## Experiment 3: Node or AZ-Style Disruption

- **Steady state:** Critical path pods are spread according to topology rules and PDBs allow controlled disruption.
- **Hypothesis:** A node/AZ-style disruption does not take down the entire critical path.
- **Injection:** Drain or disrupt one node/failure domain in the test cluster.
- **Observe:** Pod rescheduling, readiness, PDB behavior, checkout SLOs, infrastructure dashboard.
- **Success condition:** The system remains available or degrades within the stated SLO/error budget.

## Experiment 4: Kafka Disruption

- **Steady state:** Kafka consumer lag is near zero and order pipeline events converge to terminal status.
- **Hypothesis:** If Kafka is partitioned or temporarily unavailable, no business effects are lost or duplicated; the pipeline catches up after recovery.
- **Injection:** Partition or disrupt Kafka brokers/listeners using Chaos Mesh or equivalent.
- **Observe:** Producer errors, consumer lag, duplicate event handling, order terminal state convergence. Plus the machinery this actually exercises, none of which existed when this plan was first written:
  - **The outbox and its relay.** Orders does not publish to Kafka directly. `order-placed` and `refund-requested` are rows written to `outbox_events` in the same transaction as the state change, and Debezium relays them. So a Kafka outage must produce a *growing outbox table and rising relay lag*, not lost events — and the rows must drain after healing. Checking only producer errors would miss the whole mechanism, because there is no producer on this path.
  - **The dead-letter consumers.** Exhausted retries publish to `order-placed-dlt` and `refund-requested-dlt`, whose consumers open a reconciliation record without touching the durable phase. Count the records and read their `kind` and `failure_class`.
  - **Redrive.** After healing, an order left with an open incident must be redrivable to a terminal state through `POST /api/orders/{orderId}/reconciliation/redrive`. This is the operator path the outage is supposed to leave usable, and it is the half of recovery that automatic convergence does not cover.
- **Success condition:** After healing, accepted orders either reach a valid terminal state or are explicitly marked failed without duplicate reservation/payment effects. No outbox row is lost, and every incident opened during the outage is either closed by convergence or closed by a redrive.
- **A definitively declined reversal is redrivable now.** Until #122 (closing #111) Payments cached a declined refund under the deterministic key, so its redrive replayed the failure and the order had no exit. Now a redrive reaches the provider again, under a key that moves after each recorded decline — for declines recorded after #122; one cached before it still replays. If the experiment produces one, redrive it after healing and record whether it converges to `REFUNDED`; it stays stuck only if the provider declines again, and the report should say which.

## Experiment 5: CloudNativePG Primary Failover

- **Steady state:** Inventory hold writes and Orders state writes are healthy.
- **Hypothesis:** During PostgreSQL primary failover, checkout write operations may fail temporarily, but the system recovers within the stated RTO and never oversells.
- **Injection:** Trigger CloudNativePG primary failover.
- **Observe:** Checkout errors, database failover time, RTO, hold invariant metrics, traces for failed writes.
- **Success condition:** No oversell occurs, and checkout recovers within the documented RTO.

## Experiment 6: Seat Return Failure During a Post-Sale Refund

Added after the refund landed (#108). The other five experiments all predate the post-sale money
path, and none of them exercises the one sequence where a customer can end up with their money back
and a seat that never returns to sale.

- **Steady state:** Refunds complete; `orders_pipeline_inflight{status="REFUNDING"}` returns to zero
  and no `SEAT_RETURN_FAILURE` record is open.
- **Hypothesis:** If Inventory definitively refuses the seat return *after* the payment reversal has
  already succeeded, the customer keeps their money, the order stays `REFUNDING` rather than reaching
  a false terminal state, and a `SEAT_RETURN_FAILURE` incident is opened and stays open until an
  operator acts.
- **Injection:** Make Inventory refuse `POST /api/inventory/reservations/{reservationId}/refund`
  definitively while a refund is in flight — a pod kill will not do it, because an unknown outcome is
  a different case and is retried rather than recorded.
- **Observe:** Order status, `refunded_at` (must stay null), the reconciliation record's `kind` and
  `failure_class`, the seat's state in Inventory, and whether a redrive converges the order once
  Inventory is healthy again.
- **Success condition:** The order never reaches `REFUNDED` while the seat is still `SOLD`; the
  incident is visible to an operator; and a redrive after recovery completes the refund. The ordering
  is the point — money first, seat second (ADR-015) — so the failure mode under test is a withheld
  seat, never a customer who is out of pocket.
