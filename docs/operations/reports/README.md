# Local verification

These reports cover Compose drills and code regression tests. They preserve their tested revision and scope; they are separate from Kubernetes operational proof.

| Scenario | Finding | Report |
| --- | --- | --- |
| Orders SIGTERM | Accepted work preserved across HTTP drain, Kafka backlog and a stopped dependency; immediate consumer stop corrected | [Shutdown](orders-shutdown-local.md) |
| Notifications outage | 20/20 checkouts confirmed, lag caught up after restart; process-local notification history was lost | [Notifications](notifications-outage-local.md) |
| Payments outage recovery | Ten automatic confirmations after a 45 s pause; longer outage required two API redrives; no duplicate authorization observed | [Recovery](orders-recovery-local.md) |
| PostgreSQL statement failure | Real SQLSTATE/driver/listener regression converged automatically under the dependency budget; negative control failed under the shorter budget | [PostgreSQL regression](postgres-recovery-regression.md) |

[Cluster reports](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/reliability/chaos-experiment-reports.md) cover live failover, fault injection and provider audits. A local SQL statement failure does not reproduce primary promotion or connection-pool recovery. Use the [report template](../../templates/verification-report-template.md) for new evidence and update the existing scenario report when replacing a run.
