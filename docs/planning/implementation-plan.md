# Remaining work

Use the [DoD](../capstone-dod.md) for acceptance criteria and GitHub issues for current ownership and status. This plan contains unresolved dependencies, not the history of completed implementation.

| Workstream | Remaining decision or verification | Reference |
| --- | --- | --- |
| Payments | Persist an attempt before the provider call and make unknown results discoverable by order | [#142](https://github.com/g05-eurotransit/EuroTransit-application/issues/142) |
| Notifications | Decide durable customer history versus explicitly best-effort API/product expectations | [#131](https://github.com/g05-eurotransit/EuroTransit-application/issues/131) |
| Overload | Retain the verified ingress refusal/recovery scope; do not infer a global backlog capacity guarantee | [Cluster evidence](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/reliability/chaos-experiment-reports.md) |
| Delivery and observability | Reconcile actual dashboard/alert signals, tracing deployment and a GitOps release of a new application version | [Configuration readiness](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/delivery/readiness.md) |
| Final delivery | Team review of decisions, hypotheses, acceptance, postmortem, permissions and recorded demonstration | [DoD](../capstone-dod.md) |

## Slice ownership

Each owner covers application code, deployment inputs, tests, telemetry and operational evidence for the slice. Contract changes require agreement with affected owners.

| Person | Slice |
| --- | --- |
| 1 | Coordination, public contracts, gateway/security, identity and shared CI/release conventions |
| 2 | Orders, checkout state, database, lifecycle and canary |
| 3 | Inventory, reservation consistency, migrations and failover inputs |
| 4 | Payments, provider sandbox, money-path resilience and latency experiments |
| 5 | Frontend, Catalog, Notifications, blue/green and shared observability/demo evidence |

## Integration order

Roll out Inventory and Orders support for customer holds before enabling tokenless requests in the frontend or another client. Mixed older Orders versions cannot safely interpret the waiting state. Match canary images to the metric families required by the [delivery gates](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/delivery/progressive-delivery.md).

Each service owner supplies code, tests, runtime configuration inputs and evidence for their slice. Shared contract changes must update the authoritative specification and all affected producers/consumers. Operational artifacts belong to configuration; implementation and API behavior belong to application. Review permissions and merge rules are defined once in [agent governance](../governance/agent-governance.md).
