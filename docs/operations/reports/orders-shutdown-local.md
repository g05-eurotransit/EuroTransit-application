# Report: Orders Shutdown During HTTP and Kafka Work (compose)

- **Issue:** #124, local half (criteria 2 and 3; criterion 1 is [orders-shutdown-and-replay.md](../orders-shutdown-and-replay.md))
- **Environment:** docker compose, `dev` at `1ab5035` plus this branch; Orders image rebuilt before
  the drills (`docker compose build orders`)
- **Stop command:** `docker compose stop -t 35 orders` : SIGTERM, then SIGKILL after 35s, the budget
  Kubernetes leaves after the 10s preStop sleep (45s grace)
- **Tooling:** `scripts/local-checkout-load.sh` (load), `scripts/local-readiness-probe.sh` (readiness
  from inside the network), `scripts/local-consistency-check.sh SINCE` (cross-database verdict)

## Result

| Drill | Work in flight at SIGTERM | Stop took | After restart | Consistency check |
| --- | --- | --- | --- | --- |
| S1 HTTP drain | 5 checkouts/s through the gateway | 3.8s | 17 accepted (202) = 17 in the DB, 17 CONFIRMED | OK |
| S2 Kafka backlog, **before the fix** | 40 `order-placed` released at once | 7.9s | 8 done before exit, 32 after restart, 40 CONFIRMED | OK |
| S2 Kafka backlog, **after the fix** | same | 3.3s | 2 done before exit, 38 after restart, 40 CONFIRMED | OK |
| S3 dependency down | 4 checkouts waiting on a paused Payments | 5.5s, exit 143 | 4 CONFIRMED, **one** authorization each, no incident | OK |

No accepted order was lost, no reservation or payment was duplicated, and nothing was dead-lettered
by a stop. Every stop ended on SIGTERM (exit 143), none on SIGKILL.

## S1 : HTTP drain

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

## S2 : a backlog in the consumer's hands

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

## S3 : stopping while a dependency is down

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

## Conclusions

- Readiness refuses at once, the record in progress finishes, and everything not started is
  redelivered and resumed from durable state : drain, not cancellation, and no duplicate effect.
- One defect found and fixed: a stop used to process the whole polled batch (S2). Fixed by
  `immediate-stop`, proven by repeating the drill.
- Not provable on compose and left to the cluster half (#124, configuration #46): routing during the
  preStop window, the stop under real load, and a slow-but-answering dependency (compose has no
  latency injection).
