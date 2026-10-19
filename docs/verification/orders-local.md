# Orders local verification

Compose drills verified accepted-work preservation across shutdown and dependency outages. Each scenario retains its tested revision, original outcome and recovery method. These results are distinct from Kubernetes failover evidence.

## Report: Orders Shutdown During HTTP and Kafka Work (compose)

- **Issue:** #124, local half (criteria 2 and 3; criterion 1 is [orders-shutdown-and-replay.md](../design/runtime-lifecycle.md))
- **Environment:** docker compose, `dev` at `1ab5035` plus this branch; Orders image rebuilt before
  the drills (`docker compose build orders`)
- **Stop command:** `docker compose stop -t 35 orders` : SIGTERM, then SIGKILL after 35s, the budget
  Kubernetes leaves after the 10s preStop sleep (45s grace)
- **Tooling:** `scripts/local-checkout-load.sh` (load), `scripts/local-readiness-probe.sh` (readiness
  from inside the network), `scripts/local-consistency-check.sh SINCE` (cross-database verdict)

| Report element | Summary |
| --- | --- |
| Hypothesis (accepted by the team) | Stopping Orders during HTTP traffic, a Kafka backlog or a dependency outage loses no accepted order and processes none twice. |
| Steady state | Compose stack healthy; Orders rebuilt from `dev` at `1ab5035` plus the branch; a 35 s stop budget matching the Kubernetes grace. |
| Injected fault | SIGTERM through `docker compose stop -t 35 orders` in three drills: 5 checkouts/s through the gateway, 40 `order-placed` released at once, and four checkouts waiting on a paused Payments. |
| Observations | Every stop ended on SIGTERM (exit 143) in 3.3 to 7.9 s; every accepted order `CONFIRMED` after restart, one authorization each, no incident. |
| Conclusion | Held. One defect: a stop processed the whole polled batch before exiting. |
| Follow-up change | `immediate-stop` ends a listener after the record in progress (#132), proven by repeating the drill. |

### Result

| Drill | Work in flight at SIGTERM | Stop took | After restart | Consistency check |
| --- | --- | --- | --- | --- |
| S1 HTTP drain | 5 checkouts/s through the gateway | 3.8s | 17 accepted (202) = 17 in the DB, 17 CONFIRMED | OK |
| S2 Kafka backlog, **before the fix** | 40 `order-placed` released at once | 7.9s | 8 done before exit, 32 after restart, 40 CONFIRMED | OK |
| S2 Kafka backlog, **after the fix** | same | 3.3s | 2 done before exit, 38 after restart, 40 CONFIRMED | OK |
| S3 dependency down | 4 checkouts waiting on a paused Payments | 5.5s, exit 143 | 4 CONFIRMED, **one** authorization each, no incident | OK |

No accepted order was lost, no reservation or payment was duplicated, and nothing was dead-lettered
by a stop. Every stop ended on SIGTERM (exit 143), none on SIGKILL.

### S1 : HTTP drain

```
scripts/local-readiness-probe.sh > probe.log &
scripts/local-checkout-load.sh 60 2026-12-02 0.2 > load.log &
sleep 4; docker compose stop -t 35 orders
```

| Time | Event |
| --- | --- |
| 13:43:20.456 | first checkout `202` |
| 13:43:24.181 | SIGTERM |
| 13:43:24 | readiness `OUT_OF_SERVICE` (503) |
| 13:43:24.512 | dead-letter and refund consumers stopped |
| 13:43:25.283 | last `202` : served on Traefik's open connection while the web server was still up |
| 13:43:25.434 | `order-placed` consumer finished its record, stopped |
| 13:43:25.439 to .447 | "Commencing graceful shutdown" → "Graceful shutdown complete" |
| 13:43:25.566 to 27.837 | `502` from Traefik while the process exits |
| 13:43:27.988 | container stopped |
| 13:43:38 → | `405`: the route is gone and the request reaches the frontend's catch-all (compose artefact; Kubernetes answers 503 for a service with no endpoints) |

Every `202` has its order (17/17, compared by id), and all 17 converged to CONFIRMED after restart.

### S2 : a backlog in the consumer's hands

The outbox connector was paused, 40 checkouts accepted (40 rows waiting), the connector resumed, and
Orders stopped two seconds later : so the consumer held a polled batch at SIGTERM.

```
curl -X PUT localhost:8083/connectors/orders-outbox-connector/pause
scripts/local-checkout-load.sh 40 2026-12-05 0.1
curl -X PUT localhost:8083/connectors/orders-outbox-connector/resume; sleep 2
docker compose stop -t 35 orders
```

**Before the fix** (spring-kafka default, `stopImmediate=false`): SIGTERM at 13:45:14.182; the
`order-placed` consumer kept going through the rest of its batch : six more checkouts, the last
confirmed at 13:45:19.529, 5.3s after SIGTERM : and only then stopped. Harmless here at ~0.8s per
checkout, but the time is per record times batch size: with a slow dependency that still answers
(5 to 20s per checkout) a batch outlasts the 25s shutdown phase and the 35s grace, and the pod is killed
instead of stopped.

**Fix:** `spring.kafka.listener.immediate-stop: true`, also read by the dead-letter factory
(`e4e8022`).

**After the fix**, same drill (fresh travel-date fixtures): SIGTERM at 13:57:15.612; the checkout in
progress was confirmed at 13:57:16.332 and the consumer stopped at once. 38 orders were still
`ACCEPTED` with their records uncommitted; after restart all 38 were processed, 40/40 CONFIRMED, no
order processed twice.

### S3 : stopping while a dependency is down

```
docker pause eurotransit-payments
scripts/local-checkout-load.sh 4 2026-12-07 0.2; sleep 6
docker compose stop -t 35 orders
docker unpause eurotransit-payments; docker compose start orders
```

The first checkout was in its authorization attempts when SIGTERM arrived (13:47:00.428). The attempt
ran out its timeout, the error handler's back-off was cut short by the stop and the container logged
`Record in retry and not yet recovered` (13:47:03.414): no dead letter, offset not committed. Before
restart the DB held 1 `PAYMENT_PENDING` and 3 `ACCEPTED`; after it, the pending one resumed from
`PAYMENT_PENDING` and all 4 were CONFIRMED with exactly one `payment_authorizations` row each and no
reconciliation record.

### Conclusions

- Readiness refuses at once, the record in progress finishes, and everything not started is
  redelivered and resumed from durable state : drain, not cancellation, and no duplicate effect.
- One defect found and fixed: a stop used to process the whole polled batch (S2). Fixed by
  `immediate-stop`, proven by repeating the drill.
- Not provable on compose and left to the cluster half (#124, configuration #46): routing during the
  preStop window, the stop under real load, and a slow-but-answering dependency (compose has no
  latency injection).

## Report: Orders Recovery From a Payments Outage (compose)

- **Why:** on AKS (configuration #55) a 45-second Payments slowdown, an Inventory restart
  and a PostgreSQL failover each left accepted orders waiting on an operator. The retry budget was
  three attempts a second apart, and with the breaker open every attempt fails at once.
- **Change under test:** a delivery that failed because a dependency was not available gets six
  redeliveries with jittered waits of about 1, 2, 4, 8, 16 and 30 s before it is dead-lettered;
  `GET /api/orders/reconciliation` lists the open incidents, most urgent first. Policy:
  [orders-recovery-runbook.md](../operations/orders-recovery-runbook.md).
- **Environment:** docker compose, `dev` at `153eac5` plus this branch; Orders image rebuilt from the
  final code before the drills.
- **Fault:** `docker pause eurotransit-payments`: Payments stops answering, Orders' calls time out
  (3 s) and its breaker opens, the same shape as the AKS slowdown.
- **Tooling:** `scripts/local-checkout-load.sh` (one checkout a second through the gateway),
  `scripts/local-consistency-check.sh SINCE` (cross-database verdict), the redrive through the
  gateway with the local operations user.

| Report element | Summary |
| --- | --- |
| Hypothesis (accepted by the team) | With the new retry budget, a Payments outage of about 45 s converges without an operator; a longer one reaches the operator as an incident that the redrive finishes, with no duplicate authorization. |
| Steady state | Compose stack healthy; Orders rebuilt from `dev` at `153eac5` plus the change under test; one checkout a second through the gateway. |
| Injected fault | `docker pause eurotransit-payments` for 45 s (R1) and for 240 s (R2). |
| Observations | R1: 10/10 `CONFIRMED` with no incident, all within 16 s of Payments returning. R2: 5/5 `CONFIRMED` after two incidents were redriven. Consistency checks OK. |
| Conclusion | Held. |
| Follow-up change | The budget shipped in #139. The expired-payment gap the run exposed is answered by abandonment and settlement (#141). |

### Result

| Drill | Payments paused | Orders | Incidents | Operator | Consistency check |
| --- | --- | --- | --- | --- | --- |
| R1, outage shorter than the budget | 45 s (15:11:59.9 to 15:12:45.1) | 10/10 CONFIRMED | none | none | OK |
| R2, outage longer than the budget | 240 s (15:13:05.8 to 15:17:05.9) | 5/5 CONFIRMED | 2, both redriven | 2 redrives | OK |

On AKS the same 45-second slowdown ended with every affected order waiting for a manual replay.

### R1 : 45 seconds: converges without an operator

Ten checkouts from 15:11:58. The first order took its hold at 15:11:59 and met the paused Payments.
After a first attempt of 11.4 s (three timeouts), its record was redelivered five times on the
jittered waits, the attempts failing at once on the open breaker. Payments returned at 15:12:45.1; the
next redelivery, at 15:12:51.7, confirmed the order, 7 s later. The nine orders queued behind it on the
single consumer thread were confirmed in the next 8.5 seconds; all ten by 15:13:00.7. Five of the six
redeliveries were used.

### R2 : 4 minutes: the incident reaches the operator, the redrive finishes it

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

### How the budget was chosen

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

### Interpretation and current follow-up

- This local 45-second pause converged automatically. It does not prove every one-minute outage or an AKS deployment outcome.
- A longer outage ends where it did before, with a `DELIVERY_EXHAUSTED` incident, but the operator can
  now find it through the API and finish it through the redrive, with an audit line, within the seat
  hold.
- The run exposed provider-refusal classification, delayed alerting and an expired-payment recovery gap. Current Orders implements abandonment and evidence-backed settlement; see the [recovery runbook](../operations/orders-recovery-runbook.md). The older fixture was not re-audited by this documentation revision.
