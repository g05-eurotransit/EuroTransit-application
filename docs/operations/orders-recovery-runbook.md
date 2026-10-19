# Orders recovery

Recover unresolved orders through the operations API after the dependency has healed. Preserve unknown payment outcomes until retry or provider evidence establishes what happened. Use [checkout lifecycle](../design/reservation-confirmation.md#retry-and-reconciliation) for the retry policy and [SLO definitions](../design/slo.md#recovery-objectives) for the recovery objectives.

## Prerequisites

Use an operations credential for the intended environment. Check dependency health, current order state and the incident kind before acting. `reservationExpiresAt` is the operative Inventory deadline: T1 before a customer payment window opens, T2 afterward, or the original direct-checkout hold. A browser or JVM clock does not override the database decision.

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
offering only the step-4 action for that `kind` and `orderStatus` : including the evidence reference
that `settle` and `resolve` require. The `curl` calls below remain the fallback when the SPA is not
reachable, and are what the operator commands do underneath.

Orders in `HELD_AWAITING_PAYMENT` are never incidents on their own: a customer is reading the checkout
page. Only `orders_holds_overdue` above zero for more than a minute means something is wrong : the T1
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
| `DELIVERY_EXHAUSTED` | `HELD_AWAITING_PAYMENT` | Nothing to do: a later delivery took the hold and the order is waiting for the customer (ADR-016). A redrive is harmless and useless : the order parks again. The incident closes when the order ends: paid and terminal, cancelled, or cancelled by the T1 expiry job at most ten minutes later. |
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

**5c. Settle an unknown payment outcome** (operations role). Look the order up at the provider. On the
Stripe sandbox, a charge made since Payments sends the order id (#151) is found by
`metadata['order_id']`; an older one only by time window and amount (the configuration's
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
  would make "no authorization" trustworthy (#142). Accepted for delivery: the incident
  (`PAYMENT_OUTCOME_UNKNOWN`), its alert (`ReconciliationRecordOpen`) and the search by order id
  keep such a charge visible to an operator.
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
