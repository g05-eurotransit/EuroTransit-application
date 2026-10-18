# Application operations

Run the local stack with [local setup](local-dev-setup.md). For an order that stopped progressing, follow [Orders recovery](orders-recovery-runbook.md); do not infer payment outcome from a timeout.

| Need | Reference |
| --- | --- |
| Start, diagnose or stop Compose | [Local setup](local-dev-setup.md) |
| Understand SIGTERM and Kafka redelivery | [Shutdown and replay](orders-shutdown-and-replay.md) |
| Recover unresolved orders or refunds | [Recovery runbook](orders-recovery-runbook.md) |
| Read checkout targets and signal semantics | [SLO definitions](slo-observability.md) |
| Design a controlled fault experiment | [Chaos plan](chaos-experiments.md) |
| Inspect local regression/drill evidence | [Local reports](reports/README.md) |

Cluster execution, the load harness and cluster reports belong to [configuration reliability](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/reliability/chaos-experiment-reports.md).
