# Local verification

These reports cover Compose drills and code regression tests. They preserve their tested revision and scope; they are separate from Kubernetes operational proof.

| Scenario | Finding | Report |
| --- | --- | --- |
| Orders SIGTERM | Accepted work preserved across HTTP drain, Kafka backlog and a stopped dependency; immediate consumer stop corrected | [Shutdown](orders-local.md#report-orders-shutdown-during-http-and-kafka-work-compose) |
| Notifications outage | 20/20 checkouts confirmed, lag caught up after restart; the history lost in that run is now rebuilt by replay on start (#131 follow-up) | [Notifications](notifications-local.md) |
| Payments outage recovery | Ten automatic confirmations after a 45 s pause; longer outage required two API redrives; no duplicate authorization observed | [Recovery](orders-local.md#report-orders-recovery-from-a-payments-outage-compose) |
| PostgreSQL statement failure | Real SQLSTATE/driver/listener regression converged automatically under the dependency budget; negative control failed under the shorter budget | [PostgreSQL regression](postgres-recovery.md) |

[Cluster reports](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/verification/README.md) cover live failover, fault injection and provider audits. A local SQL statement failure does not reproduce primary promotion or connection-pool recovery. Use the [report template](../templates/verification-report-template.md) for new evidence and update the existing scenario report when replacing a run. Each fault experiment opens with its hypothesis, steady state, injected fault, observations, conclusion and follow-up change. The PostgreSQL regression is a code regression test, not a fault experiment, and has no such summary.
