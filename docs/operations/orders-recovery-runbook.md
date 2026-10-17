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
  **before the order's `reservationExpiresAt`**. That is the hard limit: past it the redrive refuses
  (`RESERVATION_HOLD_EXPIRED`), because authorizing a payment Inventory can no longer confirm buys only
  a charge and its reversal. The sale is then lost and the order can only be abandoned (step 5b). Other
  phases stay redrivable after the hold expires (they re-take the hold, or let Inventory refuse and the
  pipeline compensate), but should be handled within the same window.
- **Which deadline `reservationExpiresAt` is (ADR-016).** For a direct-flow order it is ten minutes
  from the hold. For a customer-hold order it is **T2**, ten minutes from the payment, once the payment
  window opened — the column is overwritten when it opens, so the list and the redrive check need
  nothing new. If the window never opened (Inventory did not answer at the payment), it is still
  **T1**, the customer's deadline, which may already be close. Such an order stays redrivable even
  past it: Payments has never been called for it, so the redrive asks Inventory for the window, gets
  the expired hold refused, and the order fails with no money moved — no abandonment needed.

The time line for an outage longer than the budget, measured on compose, leaves the operator a few
minutes, not ten. "Payment starts" is the hold in the direct flow and the payment submission in the
customer-hold flow, where T2 restarts the clock:

| | Time from the payment start |
| --- | --- |
| Payment starts, then fails | 0 |
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

The same list is in the UI at `/operations`, where an account with the `operations` realm role lands
after sign-in: one row per incident, a live countdown on each seat hold, and a single button per row
offering only the step-4 action for that `kind` and `orderStatus` — including the evidence reference
that `settle` and `resolve` require. The `curl` calls below remain the fallback when the SPA is not
reachable, and are what the operator commands do underneath.

Orders in `HELD_AWAITING_PAYMENT` are never incidents on their own: a customer is reading the checkout
page. Only `orders_holds_overdue` above zero for more than a minute means something is wrong — the T1
expiry job is not running or cannot reach the database. The seat is not at risk (Inventory's sweep
frees it at the same deadline); the order status is just stale until the job runs.

**3. Check that the cause is gone.** For `failureClass: dependency_error`, the dependency named in the
log must be healthy again (the Orders breaker state is in `resilience4j_circuitbreaker_state`).
Redriving into the same outage only exhausts the budget again.

**4. Decide by kind** and by the order's current `orderStatus`.

| `kind` | `orderStatus` | Action |
| --- | --- | --- |
| `DELIVERY_EXHAUSTED` | `ACCEPTED`, `RESERVING`, `CONFIRMING_INVENTORY`, `COMPENSATING` | Redrive. |
| `DELIVERY_EXHAUSTED` | `PAYMENT_PENDING` | Redrive **before `reservationExpiresAt`**. After it the redrive is refused: abandon instead (step 5b). Exception: a customer-hold order whose payment window never opened is still accepted, and fails unpaid (ADR-016). |
| `DELIVERY_EXHAUSTED` | `HELD_AWAITING_PAYMENT` | Nothing to do: a later delivery took the hold and the order is waiting for the customer (ADR-016). A redrive is harmless and useless — the order parks again. The incident closes when the order ends: paid and terminal, cancelled, or cancelled by the T1 expiry job at most ten minutes later. |
| `DELIVERY_EXHAUSTED`, `PAYMENT_REVERSAL_FAILURE`, `SEAT_RETURN_FAILURE` | `REFUNDING` | Redrive: it resumes the refund pipeline. |
| `PAYMENT_REVERSAL_FAILURE` | `COMPENSATING` | Redrive: it retries the reversal. |
| `PAYMENT_OUTCOME_UNKNOWN` | `COMPENSATING` | Check the provider, then settle (step 5c). |
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
| `409 RESERVATION_HOLD_EXPIRED` | `PAYMENT_PENDING` past its hold: abandon it (step 5b). |
| `409 NO_OPEN_RECONCILIATION_RECORD` | Nothing to redrive for this order. |

**5b. Abandon an expired `PAYMENT_PENDING` order** (operations role), the one the redrive refuses
with `RESERVATION_HOLD_EXPIRED`. The sale is lost: the seat is already back on sale. The command
settles the money and closes the order:

```bash
curl -s -X POST -H "Authorization: Bearer $OPS_TOKEN" \
  -H "Idempotency-Key: abandon-<orderId>-1" \
  https://<gateway>/api/orders/<orderId>/reconciliation/abandon
```

`202` with `orderStatus: COMPENSATING`. Then, depending on what Payments holds:

- **An `AUTHORIZED` payment**: it is reversed, the hold released and the order failed; the customer is
  told `CHECKOUT_FAILED` and the incidents close. Nothing else to do.
- **No authorization, or one that is not `AUTHORIZED`**: the order stays `COMPENSATING` under a new
  `PAYMENT_OUTCOME_UNKNOWN` incident. That does **not** mean no money was taken: Payments calls the
  provider before it writes its row, so a charge whose answer was lost leaves no row. Go to step 5c.
- **A reversal the provider refuses**: `PAYMENT_REVERSAL_FAILURE`, redrive it as in step 5.

Refusals: `409 RESERVATION_HOLD_NOT_EXPIRED` (the hold still runs, by the application's or the
database's clock: redrive instead), `409 ORDER_NOT_ABANDONABLE` (not `PAYMENT_PENDING`),
`409 NO_OPEN_RECONCILIATION_RECORD`.

Before settling, a redrive re-asks Payments: if Payments has recorded the authorization meanwhile, the
compensation reverses it and the incident closes by itself, with no settlement needed.

**5c. Settle an unknown payment outcome** (operations role). Look the order up at the provider (on the
Stripe sandbox, by the time window and amount; the configuration's
`scripts/reliability/provider-audit.py` enumerates a window). Then:

- **No charge, or a charge already refunded**: settle with the provider reference as evidence.
- **A charge still held**: refund it at the provider first (Payments has no row, so Orders cannot
  reverse it), then settle with the refund's reference.

```bash
curl -s -X POST -H "Authorization: Bearer $OPS_TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: settle-<orderId>-1" \
  -d '{"evidenceReference":"<provider reference>"}' \
  https://<gateway>/api/orders/<orderId>/reconciliation/<recordId>/settle
```

`202`: the incident closes with the evidence and the pipeline fails the order (`CHECKOUT_FAILED`). The
pipeline still asks Payments for the reversal first, so an authorization recorded meanwhile is reversed
rather than settled away. Refusals: `400 EVIDENCE_REFERENCE_REQUIRED`,
`404 RECONCILIATION_RECORD_NOT_FOUND`, `409 RECONCILIATION_KIND_NOT_SETTLEABLE` (only
`PAYMENT_OUTCOME_UNKNOWN`), `409 RECONCILIATION_ALREADY_RESOLVED`, `409 ORDER_NOT_SETTLEABLE`.

**6. Verify.** `GET /api/orders/{orderId}` shows a terminal status, and the order is gone from the
list: `DELIVERY_EXHAUSTED`, `PAYMENT_REVERSAL_FAILURE`, `SEAT_RETURN_FAILURE` and `PAYMENT_OUTCOME_UNKNOWN`
close by themselves
when the order reaches a terminal state. If the same incident comes back, its `attemptCount` grows:
the cause is not gone.

**Do not replay raw events instead.** Republishing an `order-placed` or `refund-requested` record to
Kafka does resume the pipeline, and the pipeline is idempotent, so it is not unsafe for the money. But
it leaves no audit line (`reconciliation.redrive` in the `audit` log), no record of who did it, no
attempt count on the incident, and it skips the hold check, so a `PAYMENT_PENDING` order past its hold
is authorized and then reversed.

## Known gaps

- **Payments does not record an authorization until the provider has answered.** A charge whose
  answer was lost leaves no row, which is why an abandoned order with no authorization needs the
  provider check of step 5c instead of failing by itself. A Payments that recorded the attempt first
  would make "no authorization" trustworthy (#142).
- **An authorization Payments records after the order has failed is not noticed.** If a provider call
  still in flight at Payments completes after the settlement's reversal found nothing, Payments writes
  an `AUTHORIZED` row for an order that is already `FAILED`, and nothing in Orders reads it. The window
  is Payments' provider timeout and retries (seconds) against a ten-minute hold, so it is small; the
  provider audit is what would find it (#142).
- **An abandonment counts as a failed checkout** (`orders_checkout_failed_total{reason="checkout_abandoned"}`).
  If it is processed by an Orders canary pod during its analysis, the canary's zero-failure gate aborts
  the rollout: avoid abandoning during a canary, or exclude that reason from the gate.
- **Payments reports definitive provider refusals as `PROVIDER_UNAVAILABLE`** (above), so Orders
  retries them as outages.
- **`ReconciliationRecordOpen` waits five minutes** before firing, which takes half of the operator's
  window. A shorter `for` belongs to the configuration repository.

## Local commands

On compose the gateway is `http://localhost` and the operations user is `admin`/`admin`:

```bash
OPS_TOKEN=$(KEYCLOAK_USERNAME=admin KEYCLOAK_PASSWORD=admin scripts/local-customer-token.sh)
```
