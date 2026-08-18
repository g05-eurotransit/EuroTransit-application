# Definition of Done: EuroTransit Marketplace

This DoD is the operational release gate for EuroTransit Marketplace. Each item must be backed by code, configuration, screenshots/logs, dashboard links, or written experiment results. The target readiness date is **2026-07-14**.

## 1. Design and Async

- [ ] Service boundaries and sync/async communication choices are documented in `design/api-design.md`.
- [ ] Architecture decisions are documented in `design/architecture-decisions.md`, including rejected alternatives and trade-offs.
- [ ] Orders implements an asynchronous pipeline with Kotlin coroutines/flows and Kafka-visible stages.
- [ ] Shutdown behavior is demonstrated: readiness refuses new traffic, in-flight work drains or cancels cooperatively, and no duplicate processing occurs after restart.
- [ ] The async design explains where suspending work reduces cost/scaling pressure and where it does not help CPU-bound work.

## 2. Consistency and Idempotency

- [ ] Inventory uses the selected Strong CP / PC-EC consistency model.
- [ ] The "never oversell" invariant is implemented with atomic 10-minute seat holds or an equivalent reservation state machine.
- [ ] Payment is attempted only after Inventory creates a valid hold.
- [ ] After payment authorization, Inventory atomically transitions the still-valid reservation `HELD -> CONFIRMED` and its seats `HELD -> SOLD` before Orders becomes `CONFIRMED`.
- [ ] Holds expire after 10 minutes or are released when payment fails/cancels.
- [ ] Concurrent confirmation, expiration, and release prove that exactly one transition wins and that confirmed/sold seats are never released by hold cleanup.
- [ ] Orders, Inventory, and Payments persist idempotency keys and return stable outcomes for duplicate requests/events.
- [ ] Duplicate `order-placed` events and retried payment authorizations cannot double-reserve or double-charge.
- [ ] A delayed hold replay returns the reservation's current state and cannot return stale `HELD` or create a second reservation for the same order.

## 3. Resilience Engineering

- [ ] Cross-service synchronous calls use Resilience4j circuit breakers with documented open/half-open settings and safe fallback behavior.
- [ ] Bulkheads isolate checkout resources from unrelated traffic.
- [ ] Every remote call has timeout and bounded retry policy with backoff and jitter.
- [ ] Overload is handled with controlled refusal or load shedding rather than unbounded queues.
- [ ] Unknown confirmation and payment outcomes preserve the durable Orders phase; exhausted Kafka retries create a reconciliation record and can be safely redriven.
- [ ] Notifications can be killed while checkout still succeeds after reservation and payment.
- [ ] Kubernetes startup/readiness/liveness probes and PodDisruptionBudgets are deliberately configured.
- [ ] Liveness probes do not fail because of transient downstream dependency failures.

## 4. Delivery, Observability, and Proof

- [ ] Two repositories are populated: application and configuration.
- [ ] CI builds/tests/publishes immutable images and updates the configuration repository.
- [ ] ArgoCD reconciles the cluster state; CI holds no cluster credentials.
- [ ] Orders canary is demonstrated with metric-gated promote/abort.
- [ ] Catalog blue/green is demonstrated with fast rollback.
- [ ] The team explains why rolling and all-at-once are not used for the critical path.
- [ ] Checkout latency and success-rate SLOs are defined in `operations/slo-observability.md`.
- [ ] RED dashboards, infrastructure USE/Golden Signals dashboards, and symptom-based alerts are live.
- [ ] A single order can be traced through gateway, Orders, Inventory, Payments, and Kafka stages.

## 5. Security and Configuration

- [ ] Traefik rejects unauthenticated requests to protected external APIs before they reach service pods.
- [ ] Services validate the authenticated principal and enforce resource ownership or privileged scopes locally.
- [ ] Order reads are authorized by owner identity or by an explicit operational/admin role.
- [ ] Inventory and Payments are internal service-to-service APIs, not public endpoints, and require service credentials plus propagated user context.
- [ ] The cluster/proof target uses Keycloak as the OIDC provider; local development may use a mock OIDC issuer or fixed development JWTs without changing production API contracts.
- [ ] Final cluster/demo proof does not depend on mocked services or fake in-memory substitutes for core infrastructure.
- [ ] TLS is terminated at Traefik in the cluster environment.
- [ ] Secrets are stored through SealedSecrets or an equivalent GitOps-safe mechanism.
- [ ] Money-path audit logs include correlation ID, principal ID, order ID, and outcome, while excluding secrets and payment details.
- [ ] No real card data or live charges are handled; Payments integrates with a provider sandbox/test API such as Stripe test mode or PayPal Sandbox.

## 6. Chaos Experiments

- [ ] The chaos plan in `operations/chaos-experiments.md` is complete.
- [ ] Latency injection into Payments proves the Orders circuit breaker opens and Catalog remains healthy.
- [ ] Inventory pod kill mid-hold proves idempotency and no oversell/double-charge.
- [ ] Node or AZ-style disruption proves PDBs/topology spread protect the critical path.
- [ ] Kafka disruption proves the async pipeline recovers without lost or duplicated business effects.
- [ ] CloudNativePG primary failover records checkout impact and observed RTO.
- [ ] Each report includes hypothesis, steady state, injected fault, observations, conclusion, and follow-up change.

## 7. Agentic Coding and Governance

- [ ] `governance/agent-governance.md` documents credentials, permissions, blast radius, review gates, and worst-case failure.
- [ ] `agent-log.md` contains at least three concrete agent mistakes and how the team detected and corrected them.
- [ ] Agent-generated delivery artifacts require human review or policy-as-code before merge to the configuration repository.

## 8. Final Deliverables

- [ ] `docs/` contains design/consistency justification, SLO definitions, chaos reports, postmortem, agent threat model, and agent log.
- [ ] A blameless postmortem is completed using `templates/postmortem-template.md`.
- [ ] A 5-minute recorded demo link is committed.
- [ ] The demo shows the running system, dashboards, canary, blue/green, one injected failure, and one alert firing.
