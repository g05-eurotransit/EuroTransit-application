# Agent governance

Agents may draft code, tests, manifests, delivery automation and documentation. The team owns service decomposition, consistency choices, SLOs, fault hypotheses, conclusions, postmortem judgments and security policy. Repository text alone does not prove that a review gate is enforced.

## Credentials and approval

Use the operator's authenticated GitHub identity so actions remain attributable. The recorded private-repository token had write scope; its operational write boundary was procedural, not technical. Describe and obtain approval for file edits, commits, pushes, comments, package installation and container starts. Reads, fetches, diffs, tests, builds and local renders do not require separate approval. Case 17 in the [agent log](../agent-log.md) explains why an accurate action description is part of this control.

Agents must not initiate cluster authentication or obtain cluster credentials or secret material. A necessary cluster read uses the operator's already-authenticated CLI and read-only access. Sandbox/placeholder configuration must stay outside tracked plaintext secrets. The broader, explicitly authorized fault-experiment exception and its actual permissions are recorded in the configuration [threat model](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/governance/agent-threat-model.md); it is not evidence of least privilege.

CI credentials are scoped to build/publish and configuration updates. They must not grant cluster access. The [delivery topology](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/gitops.md) owns the automation's actual write path.

## Review boundary

Configuration changes require human review or policy checks before merge. Changes to authentication, payments, Inventory consistency, delivery or secrets require another teammate's review. Payment and Inventory-consistency changes require a second human reviewer. Agents must not bypass Argo CD, SealedSecrets or CI controls.

A bounded review audit found nine of ten application merges without review/approval and all twelve inspected configuration merges (#36 to #53) without review. Application #126, which changed the Orders outbox schema, also had none. This finding records the audited sample, not the current state of every branch.

Branch-protection/ruleset/status-check requests returned 403 for the then-observed private repositories and account plan. Recheck repository capabilities before relying on technical enforcement. Until enforced, the review gate remains a team practice and its [DoD item](../capstone-dod.md) stays unaccepted.

## Failure impact

A merged configuration change can remove resources through pruning, undo a manual repair through self-heal, expose internal services, weaken authentication or suppress alerts. Mandatory review, scoped credentials and policy checks must cover this delivery path. An application's successful tests do not prove those controls.

## Required review checks

- Keep liveness independent of transient downstream outages; review readiness/startup separately.
- Use symptom-based alerts tied to the canonical SLIs, bounded retries with backoff/jitter and idempotent event handlers.
- Reject broad RBAC, privileged workloads, plaintext secrets, internal public routes, auth bypass and unintended destructive resources.
- Cite evidence the reviewer can resolve. A local-only or removed commit cannot substantiate a remote claim.
- Reconcile the local environment before making statements about shared branches; use the repository task runner where provided.

The [agent log](../agent-log.md) records concrete mistakes and corrections. The [documentation guide](../templates/README.md) defines writing and cross-repository ownership.

## Slice ownership

Each owner covers application code, deployment inputs, tests, telemetry and operational evidence for the slice. Contract changes require agreement with affected owners.

| Person | Slice |
| --- | --- |
| 1 | Coordination, public contracts, gateway/security, identity and shared CI/release conventions |
| 2 | Orders, checkout state, database, lifecycle and canary |
| 3 | Inventory, reservation consistency, migrations and failover inputs |
| 4 | Payments, provider sandbox, money-path resilience and latency experiments |
| 5 | Frontend, Catalog, Notifications, blue/green and shared observability/demo evidence |
