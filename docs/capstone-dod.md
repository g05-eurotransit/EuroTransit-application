# Definition of Done: EuroTransit Marketplace

This is the project acceptance checklist. Checked items retain the recorded owner acceptance; unchecked items are not automatically evidence of missing implementation. Review linked specifications and reports before changing a status. The team owns design judgments, hypotheses, targets and the final postmortem.

## 1. Design and Async

- [x] Service boundaries and sync/async communication choices are documented in `design/api-design.md`.
- [ ] Architecture decisions are documented in `design/architecture-decisions.md`, including rejected alternatives and trade-offs.
- [x] Orders implements an asynchronous pipeline with Kotlin coroutines/flows and Kafka-visible stages.
- [x] Shutdown behavior is demonstrated: readiness refuses new traffic, in-flight work drains or cancels cooperatively, and no duplicate processing occurs after restart.
- [x] The async design explains where suspending work reduces cost/scaling pressure and where it does not help CPU-bound work.

## 2. Consistency and Idempotency

- [x] Inventory uses the selected Strong CP / PC-EC consistency model.
- [x] The "never oversell" invariant is implemented with atomic time-limited seat holds or an equivalent reservation state machine.
- [x] Payment is attempted only after Inventory creates a valid hold.
- [x] After payment authorization, Inventory atomically transitions the still-valid reservation `HELD -> CONFIRMED` and its seats `HELD -> SOLD` before Orders becomes `CONFIRMED`.
- [x] Holds expire at their deadline or are released when payment fails/cancels.
- [x] Concurrent confirmation, expiration, and release prove that exactly one transition wins and that confirmed/sold seats are never released by hold cleanup.
- [x] Orders, Inventory, and Payments persist idempotency keys and return stable outcomes for duplicate requests/events.
- [x] Duplicate `order-placed` events and retried payment authorizations cannot double-reserve or double-charge.
- [x] A delayed hold replay returns the reservation's current state and cannot return stale `HELD` or create a second reservation for the same order.

## 3. Resilience Engineering

- [x] Cross-service synchronous calls use Resilience4j circuit breakers with documented open/half-open settings and safe fallback behavior.
- [x] Bulkheads isolate checkout resources from unrelated traffic.
- [x] Every remote call has timeout and bounded retry policy with backoff and jitter.
- [x] Overload is handled with controlled refusal or load shedding rather than unbounded queues.
- [x] Unknown confirmation and payment outcomes preserve the durable Orders phase; exhausted Kafka retries create a reconciliation record and can be safely redriven.
- [x] Notifications can be killed while checkout still succeeds after reservation and payment.
- [x] Kubernetes startup/readiness/liveness probes and PodDisruptionBudgets are deliberately configured.
- [x] Liveness probes do not fail because of transient downstream dependency failures.

## 4. Delivery, Observability, and Proof

- [x] Two repositories are populated: application and configuration.
- [x] CI builds/tests/publishes immutable images and updates the configuration repository.
- [x] ArgoCD reconciles the cluster state; CI holds no cluster credentials.
- [x] Orders canary is demonstrated with metric-gated promote/abort.
- [ ] Catalog blue/green is demonstrated with fast rollback.
- [x] The team explains why rolling and all-at-once are not used for the critical path.
- [x] Checkout latency and success-rate SLOs are defined in `design/slo.md`.
- [ ] RED dashboards, infrastructure USE/Golden Signals dashboards, and symptom-based alerts are live.
- [x] A single order can be traced through gateway, Orders, Inventory, Payments, and Kafka stages.

## 5. Security and Configuration

- [ ] Traefik rejects unauthenticated requests to protected external APIs before they reach service pods.
- [x] Services validate the authenticated principal and enforce resource ownership or privileged scopes locally.
- [x] Order reads are authorized by owner identity or by an explicit operational/admin role.
- [x] Inventory and Payments are internal service-to-service APIs, not public endpoints, and require service credentials plus propagated user context.
- [x] The cluster/proof target uses Keycloak as the OIDC provider; local development may use a mock OIDC issuer or fixed development JWTs without changing production API contracts.
- [x] Final cluster/demo proof does not depend on mocked services or fake in-memory substitutes for core infrastructure.
- [x] TLS is terminated at Traefik in the cluster environment.
- [x] Secrets are stored through SealedSecrets or an equivalent GitOps-safe mechanism.
- [x] Money-path audit logs include correlation ID, principal ID, order ID, and outcome, while excluding secrets and payment details.
- [x] No real card data or live charges are handled; Payments integrates with a provider sandbox/test API such as Stripe test mode or PayPal Sandbox.

## 6. Chaos Experiments

- [x] The chaos plan in `verification/strategy.md` is complete.
- [x] Latency injection into Payments proves the Orders circuit breaker opens and Catalog remains healthy.
- [x] Inventory pod kill mid-hold proves idempotency and no oversell/double-charge.
- [ ] Node or AZ-style disruption proves PDBs/topology spread protect the critical path.
- [ ] Kafka disruption proves the async pipeline recovers without lost or duplicated business effects.
- [ ] CloudNativePG primary failover records checkout impact and observed RTO.
- [ ] Each report includes hypothesis, steady state, injected fault, observations, conclusion, and follow-up change.

## 7. Agentic Coding and Governance

- [x] `governance/agent-governance.md` documents credentials, permissions, blast radius, review gates, and worst-case failure.
- [x] `agent-log.md` contains at least three concrete agent mistakes and how the team detected and corrected them.
- [ ] Agent-generated delivery artifacts require human review or policy-as-code before merge to the configuration repository.

## 8. Final Deliverables

- [ ] `docs/` contains design/consistency justification, SLO definitions, chaos reports, postmortem, agent threat model, and agent log.
- [ ] A blameless postmortem is completed using `templates/postmortem-template.md`.
- [ ] A 5-minute recorded demo link is committed.
- [ ] The demo shows the running system, dashboards, canary, blue/green, one injected failure, and one alert firing.

## Acceptance sources

- [Contracts](design/api-design.md), [lifecycle](design/reservation-confirmation.md), [decisions](design/architecture-decisions.md) and [SLOs](design/slo.md) own the rules.
- [Local reports](verification/README.md) and the [cluster experiment index](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/verification/README.md) own observations and their limits.
- [Configuration readiness](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/readiness.md) records environment gaps.
- [Agent governance](governance/agent-governance.md) and [agent log](agent-log.md) provide permission/review policy and concrete corrections.

## Remaining acceptance work

Use linked issues for current ownership and status. This table adds no acceptance criteria.

| Workstream | Remaining decision or verification | Reference |
| --- | --- | --- |
| Payments | Persist an attempt before the provider call and make unknown results discoverable by order | [#142](https://github.com/g05-eurotransit/EuroTransit-application/issues/142) |
| Notifications | Decide durable customer history versus explicitly best-effort API/product expectations | [#131](https://github.com/g05-eurotransit/EuroTransit-application/issues/131) |
| Overload | Retain the verified ingress refusal/recovery scope; do not infer a global backlog capacity guarantee | [Cluster evidence](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/verification/README.md) |
| Delivery and observability | Reconcile actual dashboard/alert signals, tracing deployment and a GitOps release of a new application version | [Configuration readiness](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/readiness.md) |
| Final delivery | Team review of decisions, hypotheses, acceptance, postmortem, permissions and recorded demonstration | [DoD](capstone-dod.md) |

## Recorded demonstration

This is a recording plan, not a completed demo or a fabricated video link.
Record after the [configuration readiness gates](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/readiness.md) close.

| Time | Show | Required evidence |
| --- | --- | --- |
| 0:00 to 0:40 | Running system, five services, single ingress, GitOps revision and immutable images | Actual cluster and Argo state; explain any temporary controller pause |
| 0:40 to 1:20 | Checkout RED/end-to-end view and one trace | Real confirmed order, terminal SLI, gateway/service/Kafka spans |
| 1:20 to 2:20 | Orders canary exposure, gate decision, promote and abort recovery | Candidate hash, Traefik weight, AnalysisRun samples and stable route |
| 2:20 to 3:10 | Catalog blue/green preview, switch and rollback | Distinct revisions, preview check, active route and retained old revision |
| 3:10 to 4:20 | One bounded fault, symptom alert and recovery | Actual fault interval, firing alert, user-visible impact and cleanup |
| 4:20 to 5:00 | Invariants and recovery trade-offs | Independent provider audit, one-seat crash proof, factual failed-availability finding |

Historical alert evidence from the recorded run is available in the observability
report; label it as replayed evidence with its source run/revision. A historical screenshot
must not be presented as an alert firing live during the recording.

Before release, commit the real recording URL and revisions here, keep provider
identifiers/credentials out of the recording and complete team review of the
hypotheses/postmortem. The recording URL is still pending.
