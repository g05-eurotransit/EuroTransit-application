# Orders: Recovery Policy and Operator Runbook

What Orders recovers by itself after a failure, what it hands to an operator, how fast each must
happen, and the steps an operator follows. The policy is read from the code; the numbers are measured
on the compose stack ([recovery report](reports/2026-10-orders-recovery-local.md)). It answers the
open gate of the AKS experiments of 2026-10-12 (configuration #55): there, a Payments slowdown, an
Inventory restart and a PostgreSQL failover each left accepted orders waiting on an operator, and they
were recovered by replaying raw events rather than through this runbook.

## The policy

**An unknown outcome is never turned into a decision.** A checkout or refund whose dependency does not
answer keeps its durable phase (`RESERVING`, `PAYMENT_PENDING`, `CONFIRMING_INVENTORY`,
`COMPENSATING`, `REFUNDING`) and is retried; it is never failed or compensated on a guess
(`reservation-confirmation.md`, invariant 8). What changes with the length of the failure is only *who*
finishes it.

| Failure | Who finishes it | How |
| --- | --- | --- |
| Pod stopped, rebalance, Kafka unreachable | Orders, by itself | The record is redelivered and resumes from the durable phase ([shutdown and replay](orders-shutdown-and-replay.md)). |
| A dependency not available for up to about a minute: timeout, open breaker, full bulkhead, connection refused, a 5xx, a 408 or 429, Keycloak or the database unreachable | Orders, by itself | Six redeliveries, with waits of about 1, 2, 4, 8, 16 and 30 s each moved at random by up to a quarter: 48 to 67 s of waits, 1.5 to about 2.3 minutes in real time (`checkoutErrorHandler` in `KafkaConfiguration.kt`). |
| Anything else, or a dependency outage longer than that | An operator | After three attempts a second apart (or the budget above), the delivery is dead-lettered and becomes a `DELIVERY_EXHAUSTED` incident; the operator redrives it once the cause is gone. A 4xx with no business code, a service credential Keycloak refuses or a statement the database rejects is a deployment or data bug: it is labelled `dependency_error` but gets the short budget, since waiting cannot fix it. |
| A definitive refusal Orders cannot settle on its own | An operator | `PAYMENT_REVERSAL_FAILURE`, `SEAT_RETURN_FAILURE` (redrive) or `INVARIANT_VIOLATION` (investigate, then resolve). |

**Why the automatic budget is about a minute and not longer.** Each listener has one consumer thread
per pod, so while one record is being retried every checkout behind it on that pod waits too. And an
outage that outlasts the budget reaches the operator later, closer to the deadline below. A minute
covers the outages measured so far: a PostgreSQL failover in seconds, a 45-second Payments slowdown
with a retry to spare. The waits are jittered so that the orders of one outage, on every pod, do not
hit the recovering dependency at the same instants.

**While a record waits, its consumer does not poll.** A rebalance during an outage (a rollout, a pod
restart) therefore waits for this member to come back from its current wait, up to 30 s plus the
attempt, and every pod's checkouts pause with it.

**The cost to know about.** The retry budget follows the failure *class*, and the class follows what
the dependency reports. Payments currently reports **every** provider exception as
`PROVIDER_UNAVAILABLE`, including a definitive refusal such as Stripe's `token_already_used`. Such an
order (Payments answers `503`) is retried for the whole budget although it can never succeed, and the
checkouts behind it on that pod wait. On compose this held a pod's checkouts for over two minutes (with the two-minute budget
first tried). Recognise it by a stalled pipeline while Payments is healthy; the order then reaches the
operator as a `DELIVERY_EXHAUSTED` incident like any other.

## Recovery objectives

Proposed by the Orders owner; the team confirms them in [slo-observability.md](slo-observability.md).

- **Automatic convergence.** An accepted order caught by a dependency outage of up to about a minute
  reaches a terminal state without an operator, within about a minute of the dependency returning.
- **Operator response.** A `DELIVERY_EXHAUSTED` incident on a `PAYMENT_PENDING` order is redriven
  **before the order's `reservationExpiresAt`**. That is the hard limit: Inventory's hold lasts ten
  minutes from the reservation, and past it the redrive refuses (`RESERVATION_HOLD_EXPIRED`), because
  authorizing a payment Inventory can no longer confirm buys only a charge and its reversal. Other
  phases stay redrivable after the hold expires (they re-take the hold, or let Inventory refuse and the
  pipeline compensate), but should be handled within the same window.

The time line for an outage longer than the budget, measured on compose, leaves the operator a few
minutes, not ten:

| | Time from the hold |
| --- | --- |
| Hold taken, payment fails | 0 |
| Retry budget exhausted, incident opened | 1.5 to about 2.3 min |
| `ReconciliationRecordOpen` fires (`for: 5m`) | 6.5 to about 7.3 min |
| Hold expires; a `PAYMENT_PENDING` redrive is refused from here | 10 min |

## Runbook

**1. The alert.** `ReconciliationRecordOpen` fires: at least one incident is open. The incident's
order id is also in the Orders log line `Reconciliation required for order …` (level `ERROR`).

**2. List the open incidents** (operations role):

```bash
curl -s -H "Authorization: Bearer $OPS_TOKEN" https://<gateway>/api/orders/reconciliation
```

Most urgent first: incidents whose order still has a live seat hold, nearest deadline first; then
those with no hold deadline, oldest first; then those past their hold. Each with `kind`, `orderState` (the phase that failed), `orderStatus` (where the order is
now), `failureClass`, `attemptCount`, `createdAt` and the order's `reservationExpiresAt`. Work the one closest to its `reservationExpiresAt` first.

**3. Check that the cause is gone.** For `failureClass: dependency_error`, the dependency named in the
log must be healthy again (the Orders breaker state is in `resilience4j_circuitbreaker_state`).
Redriving into the same outage only exhausts the budget again.

**4. Decide by kind** and by the order's current `orderStatus`.

| `kind` | `orderStatus` | Action |
| --- | --- | --- |
| `DELIVERY_EXHAUSTED` | `ACCEPTED`, `RESERVING`, `CONFIRMING_INVENTORY`, `COMPENSATING` | Redrive. |
| `DELIVERY_EXHAUSTED` | `PAYMENT_PENDING` | Redrive **before `reservationExpiresAt`**. After it the redrive is refused: see "Known gaps". |
| `DELIVERY_EXHAUSTED`, `PAYMENT_REVERSAL_FAILURE`, `SEAT_RETURN_FAILURE` | `REFUNDING` | Redrive: it resumes the refund pipeline. |
| `PAYMENT_REVERSAL_FAILURE` | `COMPENSATING` | Redrive: it retries the reversal. |
| `INVARIANT_VIOLATION` | `FAILED` | Do not redrive. Investigate, then resolve with an evidence reference (`POST /api/orders/{orderId}/reconciliation/{recordId}/resolve`, [api-design.md](../design/api-design.md)). |

**5. Redrive** (operations role; a new idempotency key per attempt, the same key to repeat one):

```bash
curl -s -X POST -H "Authorization: Bearer $OPS_TOKEN" \
  -H "Idempotency-Key: redrive-<orderId>-1" \
  https://<gateway>/api/orders/<orderId>/reconciliation/redrive
```

`202` with the order's current status: the pipeline resumes asynchronously from its durable phase.
Refusals:

| Response | Meaning |
| --- | --- |
| `409 ORDER_ALREADY_TERMINAL` | The order converged meanwhile; the stale incident is closed. Nothing to do. |
| `409 RESERVATION_HOLD_EXPIRED` | `PAYMENT_PENDING` past its hold. See "Known gaps". |
| `409 NO_OPEN_RECONCILIATION_RECORD` | Nothing to redrive for this order. |

**6. Verify.** `GET /api/orders/{orderId}` shows a terminal status, and the order is gone from the
list: `DELIVERY_EXHAUSTED`, `PAYMENT_REVERSAL_FAILURE` and `SEAT_RETURN_FAILURE` close by themselves
when the order reaches a terminal state. If the same incident comes back, its `attemptCount` grows:
the cause is not gone.

**Do not replay raw events instead.** Republishing an `order-placed` or `refund-requested` record to
Kafka does resume the pipeline, and the pipeline is idempotent, so it is not unsafe for the money. But
it leaves no audit line (`reconciliation.redrive` in the `audit` log), no record of who did it, no
attempt count on the incident, and it skips the hold check, so a `PAYMENT_PENDING` order past its hold
is authorized and then reversed.

## Known gaps

- **A `PAYMENT_PENDING` order whose hold has expired has no exit.** The redrive refuses it and
  `resolve` accepts only `INVARIANT_VIOLATION`, so the order and its incident stay open. Example on
  compose: an order of 2026-09-16 still open on 2026-10-13. Needs a design decision.
- **Payments reports definitive provider refusals as `PROVIDER_UNAVAILABLE`** (above), so Orders
  retries them as outages.
- **`ReconciliationRecordOpen` waits five minutes** before firing, which takes half of the operator's
  window. A shorter `for` belongs to the configuration repository.

## Local commands

On compose the gateway is `http://localhost` and the operations user is `admin`/`admin`:

```bash
OPS_TOKEN=$(KEYCLOAK_USERNAME=admin KEYCLOAK_PASSWORD=admin scripts/local-customer-token.sh)
```
