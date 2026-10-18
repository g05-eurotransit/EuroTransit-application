# Report: Orders Recovery From a Payments Outage (compose)

- **Why:** on AKS (configuration #55) a 45-second Payments slowdown, an Inventory restart
  and a PostgreSQL failover each left accepted orders waiting on an operator. The retry budget was
  three attempts a second apart, and with the breaker open every attempt fails at once.
- **Change under test:** a delivery that failed because a dependency was not available gets six
  redeliveries with jittered waits of about 1, 2, 4, 8, 16 and 30 s before it is dead-lettered;
  `GET /api/orders/reconciliation` lists the open incidents, most urgent first. Policy:
  [orders-recovery-runbook.md](../orders-recovery-runbook.md).
- **Environment:** docker compose, `dev` at `153eac5` plus this branch; Orders image rebuilt from the
  final code before the drills.
- **Fault:** `docker pause eurotransit-payments`: Payments stops answering, Orders' calls time out
  (3 s) and its breaker opens, the same shape as the AKS slowdown.
- **Tooling:** `scripts/local-checkout-load.sh` (one checkout a second through the gateway),
  `scripts/local-consistency-check.sh SINCE` (cross-database verdict), the redrive through the
  gateway with the local operations user.

## Result

| Drill | Payments paused | Orders | Incidents | Operator | Consistency check |
| --- | --- | --- | --- | --- | --- |
| R1, outage shorter than the budget | 45 s (15:11:59.9 to 15:12:45.1) | 10/10 CONFIRMED | none | none | OK |
| R2, outage longer than the budget | 240 s (15:13:05.8 to 15:17:05.9) | 5/5 CONFIRMED | 2, both redriven | 2 redrives | OK |

On AKS the same 45-second slowdown ended with every affected order waiting for a manual replay.

## R1 : 45 seconds: converges without an operator

Ten checkouts from 15:11:58. The first order took its hold at 15:11:59 and met the paused Payments.
After a first attempt of 11.4 s (three timeouts), its record was redelivered five times on the
jittered waits, the attempts failing at once on the open breaker. Payments returned at 15:12:45.1; the
next redelivery, at 15:12:51.7, confirmed the order, 7 s later. The nine orders queued behind it on the
single consumer thread were confirmed in the next 8.5 seconds; all ten by 15:13:00.7. Five of the six
redeliveries were used.

## R2 : 4 minutes: the incident reaches the operator, the redrive finishes it

Five checkouts from 15:13:04. The first was confirmed before the pause. The next ones, in turn:

| Order | Hold taken | Incident opened | Hold expires | Outcome |
| --- | --- | --- | --- | --- |
| `d7d73aff` | 15:13:05.5 | 15:14:48.3 (+1 min 43 s) | 15:23:05.5 | redriven 15:17:36.9, CONFIRMED 15:17:39.1 |
| `d6caf52b` | 15:14:48.3 | 15:16:19.3 (+1 min 31 s) | 15:24:48.3 | redriven 15:17:36.9, CONFIRMED 15:17:38.8 |
| `63b03052` | 15:16:19.3 | : | : | converged by itself 15:17:35.7, 30 s after Payments returned |
| `a6d87d8f` | queued | : | : | CONFIRMED 15:17:36.5 |

`GET /api/orders/reconciliation` listed the two incidents first, nearest hold deadline first, and the
older incident (past its hold, see below) after them. Each redrive answered `202`, the pipeline
resumed from `PAYMENT_PENDING` under the same Payments idempotency key, and both incidents closed by
themselves when the orders were confirmed. Nothing was replayed by hand and no database row was touched.

**The operator's window.** The first incident opened 1 min 43 s after its hold; the hold had
8 min 17 s left. `ReconciliationRecordOpen` fires five minutes after an incident opens, which would
have left 3 min 17 s to redrive it before `RESERVATION_HOLD_EXPIRED`.

## How the budget was chosen

The change was first tried with 121 seconds of waits, then with 61 seconds without jitter. Both passed
R1 and R2 the same way. Two findings from the first made the budget about a minute, and the review
added the jitter (agent-governance.md: retry settings include backoff and jitter) and sent deployment
bugs labelled as dependency failures (a 4xx with no business code, a refused service credential) back
to the short budget:

- **Head-of-line blocking was real.** `compose-up` with a fresh Kafka makes Debezium snapshot the
  outbox and republish every historical event (a local artefact). One of them was an order of
  the older fixture still in `PAYMENT_PENDING`, whose single-use payment token Stripe refused
  (`400 token_already_used`). Payments reports that refusal as `PROVIDER_UNAVAILABLE`, so Orders
  classified it as a dependency failure and retried it for the whole budget: every checkout on the pod
  waited 2 min 18 s behind it (13:59:10 to 14:01:28). With the old budget that would have been about 3 s.
- **The incident came late.** With an outage longer than the budget, the incident opened 3 min 06 s
  after the hold, leaving the operator under two minutes once the five-minute alert had fired.

## Interpretation and current follow-up

- This local 45-second pause converged automatically. It does not prove every one-minute outage or an AKS deployment outcome.
- A longer outage ends where it did before, with a `DELIVERY_EXHAUSTED` incident, but the operator can
  now find it through the API and finish it through the redrive, with an audit line, within the seat
  hold.
- The run exposed provider-refusal classification, delayed alerting and an expired-payment recovery gap. Current Orders implements abandonment and evidence-backed settlement; see the [recovery runbook](../orders-recovery-runbook.md). The older fixture was not re-audited by this documentation revision.
