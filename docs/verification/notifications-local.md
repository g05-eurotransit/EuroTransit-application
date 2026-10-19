# Report: Checkout With Notifications Down, and Its Catch-Up (compose)

- **Issue:** #124, local half (criteria 4 and 5). The load-driven version and the SLI measured under
  load need the load runner (#123, configuration #46) and stay with the cluster half.
- **Environment:** docker compose, `dev` at `1ab5035` plus this branch
- **Contract under test:** ADR-004 : *"If Notifications is down, Kafka retains the event and checkout
  remains successful"*; SLI "Notification degradation" (`slo-observability.md`): checkout failures
  attributed to Notifications / checkout attempts, target 99.9% unaffected.

| Report element | Summary |
| --- | --- |
| Hypothesis (accepted by the team) | If Notifications is down, Kafka retains the event and checkout remains successful (ADR-004), and Notifications catches up after its restart. |
| Steady state | Compose stack healthy; `dev` at `1ab5035` plus the branch; the test customer at its 50-notification history cap. |
| Injected fault | `docker compose stop notifications` for 48 s while 20 checkouts ran. |
| Observations | 20/20 `CONFIRMED`, none failed because of Notifications; lag 20, back to 0 about 4 s after the restart; the 20 notifications delivered; the 50 from before the outage gone. |
| Conclusion | Held for checkout and catch-up. The history did not survive the restart. |
| Follow-up change | None for checkout. For the history, #131: Notifications replays the topic on start, verified [below](#follow-up-history-replay-131). |

## Result

| Check | Expected | Observed |
| --- | --- | --- |
| Checkouts during the outage | all succeed | 20/20 accepted, 20/20 CONFIRMED; consistency check OK |
| Checkout failures attributable to Notifications | 0 | 0 : no `orders_checkout_failed_total` series above zero; Orders has no call to Notifications to fail on |
| Events retained | lag grows by 20 | group `notifications` on `notification-requested`: offset 76, end 96, lag 20 |
| Catch-up after restart | lag back to 0, 20 processed | lag 0 about 4s after start; `notifications_events_consumed_total{result="processed"}` 20 |
| Notifications delivered | the 20 orders | `GET /api/notifications`: 20 entries, all `ORDER_CONFIRMED`, exactly the 20 order ids accepted during the outage |
| History from before the outage | : (see finding) | gone: 50 entries before, 20 after |

## Procedure

```
docker compose stop notifications                          # 13:48:50
scripts/local-checkout-load.sh 20 2026-12-08 0.2           # 20 x 202
sleep 25; scripts/local-consistency-check.sh 2026-10-08T13:48:50
docker exec eurotransit-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group notifications
docker compose start notifications                         # 13:49:38
```

While Notifications is stopped, `GET /api/notifications` through the gateway answers `200` with the
frontend's HTML : the compose route is gone and the request falls through to the catch-all. That is a
compose artefact; it is not a Notifications or Orders response.

## Finding : the notification history does not survive a restart

`NotificationStore` is in memory ("best-effort by design (no persistence)"). The customer had 50
notifications before the outage (the per-customer cap) and only the 20 new ones after the restart.
ADR-004 holds : nothing was lost from Kafka and no checkout was affected : but `api-design.md`
the original Notifications contract described customer "stored notification history",
which every restart, deploy or reschedule empties. Not an Orders defect; reported to the
Notifications owner as #131.

## Interpretation

- Checkout is isolated from Notifications by construction: the notification is an outbox row
  committed with the order's terminal state, relayed by Debezium, consumed asynchronously.
- Kafka retains the events and Notifications catches up from its committed offset on restart.
- Its read model starts empty at each restart : a contract question for Notifications, #131.

The current [API contract](../design/api-design.md#notifications) keeps the history best effort by decision; durable history is possible later work, tracked in #131.

## Follow-up: history replay (#131)

- **Environment:** docker compose (`kafka`, `keycloak`, `traefik`, `notifications`), `main` at `f327844` plus the #131 branch
- **Change:** every partition assignment seeks `notification-requested` to the beginning; the store is idempotent by event id and ordered by `occurredAt`; replayed records older than five minutes go to history only.

| Check | Expected | Observed |
| --- | --- | --- |
| History before restart | 5 published for `alice` | `GET /api/notifications`: 5, newest first |
| History after `docker compose restart notifications` | same 5 | 5, same order, served 5 s after the restart; log `Replaying notification-requested from the beginning` |
| Live stream after restart, history under five minutes old | replayed records pushed (grace window) plus the new one | 5 replayed + 1 new |
| Live stream after restart, history over five minutes old | only the new record | only the new record; history 7 |
| Group `notifications` lag during a replay of 507 records | no sustained lag (`KafkaPipelineStalled` needs > 100 for 15 min) | committed 507 / end 507, lag 0 at every sample from 0 to 35 s; 507 records re-recorded in under 2 s |

Events were published directly to the topic with `kafka-console-producer.sh` in the `notification-requested` envelope, not produced by checkouts: the drill tests the read model, not the money path. The history is bounded by the topic retention (7 days on the cluster) and the 50-entry cap; it is a projection, not a durable store.
