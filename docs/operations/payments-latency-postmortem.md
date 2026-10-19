# Payments latency postmortem

On AKS, a 45-second Payments slowdown left every checkout accepted during it waiting on an operator. The Orders Payments breaker opened and Catalog stayed healthy, but Orders gave up on each order within about three seconds of the breaker opening. The orders stayed `PAYMENT_PENDING` until their original `order-placed` events were replayed by hand. All eight accepted orders then confirmed, with eight charges and no duplicate. After the corrections below, the same fault left one order of six to the operator; the other five confirmed without one.

## Cause and contributing conditions

The fault was injected on purpose: a 4000 ms delay on the Payments pod's port 8080 for 45 s, with Orders at `bad4c04`. The delay also timed out Payments' own probes, so Payments was likely removed from its Service and restarted within the window, as the later run confirmed.

- **The retry budget was shorter than the outage.** Orders redelivered a failed `order-placed` record three times, one second apart. With the breaker open every attempt fails at once, so the budget ran out in about three seconds, well inside a 45-second outage. The orders were marked `DELIVERY_EXHAUSTED` and kept, which is safe, but nothing resumed them when Payments recovered.
- **The operator had no supported way back.** No API listed the open incidents, and a `PAYMENT_PENDING` order whose hold had expired had no exit at all: the redrive refuses it and nothing else accepted it. Recovery used a scoped replay of the raw events, which leaves no audit line and skips the hold check.
- **The alert left little time.** `ReconciliationRecordOpen` waited five minutes before firing, against a ten-minute seat hold.
- **Payments records an authorization only after Stripe answers** ([#142](https://github.com/g05-eurotransit/EuroTransit-application/issues/142)). This did not cause the incident. It is why "no authorization" cannot prove that no money was taken when an order is abandoned.

## Observed sequence

Before injection, all services were Ready, the Orders Payments breaker was `CLOSED` and Catalog answered every probe. The checkout and recovery objectives are in [slo.md](../design/slo.md#recovery-objectives).

| Elapsed time | Event and evidence |
| --- | --- |
| T+0 s | Delay enabled on Payments, with an independent 45 s expiry |
| T+12 s | Orders Payments breaker first observed `OPEN`; the retry budget of the accepted orders runs out within seconds |
| T+37 s | `HALF_OPEN` first observed |
| T+45 s | Delay removed, original queueing discipline restored |
| After healing | Six fault-run orders stay `PAYMENT_PENDING`; of two later recovery orders, one confirms and one stays pending |
| T+366 to T+426 s | `ReconciliationRecordOpen` firing in Prometheus |
| Recovery | The six original events, then the two recovery-run events, replayed by hand |
| From T+394 s | Final audit: all eight `CONFIRMED`, eight matching Stripe charges, no duplicate |

Catalog returned HTTP 200 for all 129 probes, p95 137.5 ms.

**Rerun after the corrections** (Orders `45357ef`, same fault and method):

| Elapsed time | Event and evidence |
| --- | --- |
| T+5 s | First order placed; Payments authorizes it at T+6 s but answers late |
| T+8 to T+76 s | Payments NotReady, restarted once at about T+44 s |
| T+11 s | Breaker `OPEN`, then alternating with `HALF_OPEN` |
| T+45 s | Delay removed |
| T+74 s | First order's backed-off retries run out: `DELIVERY_EXHAUSTED`; the incident is listed by `GET /api/orders/reconciliation` |
| T+116 to T+118 s | Breaker `CLOSED`; the other five orders `CONFIRMED` without an operator, the last about 72 s after the fault ended |
| T+605 s | The first order's seat hold expires |
| T+618 s | Redrive refused, `409 RESERVATION_HOLD_EXPIRED` (audited), as the redrive policy requires |
| T+958 to T+961 s | Operator abandons the order (runbook step 5b): the charge is refunded, the seats are released, the order ends `FAILED` and its incident closes |

Two recovery-run orders confirmed in 1.1 to 1.8 s. Independent Stripe audits matched six and two charges with nothing unmatched.

## Controls and gaps

**What contained it.**
- Timeouts and the breaker bounded the waiting, and Catalog stayed healthy.
- Idempotency keys kept every retry and every replay to one charge per order.
- `DELIVERY_EXHAUSTED` kept accepted orders durable instead of failing them on an unknown outcome.
- The reconciliation alert fired.

**What did not.**
- Fault removal alone did not resume the orders.
- The operator's only tool was an event replay.
- An order whose hold expired would have stayed open for ever.

The replay was a mitigation, not a fix.

**What the rerun still showed.** The redrive came 13 s after the hold expired. On AKS no client allows a password grant, so the operations token had to be copied from the browser, and it expires after about five minutes. The operations console now covers listing, redrive and abandonment in the browser; it has not yet been used on the cluster.

## Follow-up actions

| Action | Owner | Completion criterion / evidence | Status |
| --- | --- | --- | --- |
| Ride out a short dependency outage: six backed-off retries, 1.5 to about 2.3 minutes, counted from the first failure ([#139](https://github.com/g05-eurotransit/EuroTransit-application/pull/139)) | Orders | Rerun: five of six orders confirmed without an operator | Complete |
| List open incidents for operators ([#139](https://github.com/g05-eurotransit/EuroTransit-application/pull/139)) | Orders | Rerun: the exhausted order listed by `GET /api/orders/reconciliation` | Complete |
| Let an operator abandon an expired `PAYMENT_PENDING` order, refunding an authorized charge ([#141](https://github.com/g05-eurotransit/EuroTransit-application/pull/141)) | Orders | Rerun: abandoned, refunded, `FAILED`, incident closed | Complete |
| Operations console for incidents, redrive and abandonment ([#152](https://github.com/g05-eurotransit/EuroTransit-application/pull/152)) | Frontend | An operator recovers an order from the browser on AKS | Complete in code; not yet used on AKS |
| Alert within the hold window: open record after one minute, a page for `PAYMENT_OUTCOME_UNKNOWN` (configuration [#59](https://github.com/g05-eurotransit/EuroTransit-configuration/issues/59), [#57](https://github.com/g05-eurotransit/EuroTransit-configuration/pull/57)) | Observability | Rules merged and evaluated on AKS | Open |
| Charges carry the order id ([#151](https://github.com/g05-eurotransit/EuroTransit-application/pull/151)) | Payments | A charge found by `metadata['order_id']` | Complete |
| Record the payment attempt before the provider call ([#142](https://github.com/g05-eurotransit/EuroTransit-application/issues/142)) | Payments | A refund answers `NOT_FOUND` only for an order never sent to the provider | Accepted for delivery |
| Least-privilege identity for fault injection | Platform | Experiments run without the documented credential exception | Open |

## Evidence

- [Payments latency and restart, both runs](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/verification/aks/dependencies.md#payments-latency-and-restart) and the [latency-only run](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/verification/aks/dependencies.md#payments-latency).
- [Factual incident timeline](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/verification/incidents/payments-recovery.md).
- [Local Payments outage recovery](../verification/orders-local.md#report-orders-recovery-from-a-payments-outage-compose).
- [Orders recovery runbook](orders-recovery-runbook.md).

Run artifacts, provider audits and raw identifiers stay outside Git.
