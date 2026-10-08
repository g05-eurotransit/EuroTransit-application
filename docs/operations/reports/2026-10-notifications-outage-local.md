# Report: Checkout With Notifications Down, and Its Catch-Up (compose)

- **Date:** 2026-10-08, times UTC
- **Issue:** #124, local half (criteria 4 and 5). The load-driven version and the SLI measured under
  load need the load runner (#123, configuration #46) and stay with the cluster half.
- **Environment:** docker compose, `dev` at `1ab5035` plus this branch
- **Contract under test:** ADR-004 — *"If Notifications is down, Kafka retains the event and checkout
  remains successful"*; SLI "Notification degradation" (`slo-observability.md`): checkout failures
  attributed to Notifications / checkout attempts, target 99.9% unaffected.

## Result

| Check | Expected | Observed |
| --- | --- | --- |
| Checkouts during the outage | all succeed | 20/20 accepted, 20/20 CONFIRMED; consistency check OK |
| Checkout failures attributable to Notifications | 0 | 0 — no `orders_checkout_failed_total` series above zero; Orders has no call to Notifications to fail on |
| Events retained | lag grows by 20 | group `notifications` on `notification-requested`: offset 76, end 96, lag 20 |
| Catch-up after restart | lag back to 0, 20 processed | lag 0 about 4s after start; `notifications_events_consumed_total{result="processed"}` 20 |
| Notifications delivered | the 20 orders | `GET /api/notifications`: 20 entries, all `ORDER_CONFIRMED`, exactly the 20 order ids accepted during the outage |
| History from before the outage | — (see finding) | gone: 50 entries before, 20 after |

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
frontend's HTML — the compose route is gone and the request falls through to the catch-all. That is a
compose artefact; it is not a Notifications or Orders response.

## Finding — the notification history does not survive a restart

`NotificationStore` is in memory ("best-effort by design (no persistence)"). The customer had 50
notifications before the outage (the per-customer cap) and only the 20 new ones after the restart.
ADR-004 holds — nothing was lost from Kafka and no checkout was affected — but `api-design.md`
§Notifications describes `GET /api/notifications` as the customer's "stored notification history",
which every restart, deploy or reschedule empties. Not an Orders defect; reported to the
Notifications owner as #131.

## Conclusions

- Checkout is isolated from Notifications by construction: the notification is an outbox row
  committed with the order's terminal state, relayed by Debezium, consumed asynchronously.
- Kafka retains the events and Notifications catches up from its committed offset on restart.
- Its read model starts empty at each restart — a contract question for Notifications, #131.
