# Definition of Done: EuroTransit Marketplace

This DoD is the operational release gate for EuroTransit Marketplace. Each item must be backed by code, configuration, screenshots/logs, dashboard links, or written experiment results. The target readiness date is **2026-07-14**.

**How the boxes are ticked.** An item is ticked by the owner of that item, and carries the evidence the paragraph above asks for. **An unticked box therefore means "not yet claimed by its owner", not "not done"** — the two are different, and reading the first as the second understates where the project is. The Orders-owned items were audited against the code on 2026-09-18 and are ticked below; every other item is left exactly as it was and says nothing either way.

## 1. Design and Async

- [ ] Service boundaries and sync/async communication choices are documented in `design/api-design.md`.
- [ ] Architecture decisions are documented in `design/architecture-decisions.md`, including rejected alternatives and trade-offs.
- [x] Orders implements an asynchronous pipeline with Kotlin coroutines/flows and Kafka-visible stages.
      *Orders, 2026-09-18:* `OrderPlacedEventListener` / `RefundRequestedEventListener` drive suspending stages in `CheckoutOrchestrator` and `RefundOrchestrator`. Every stage transition is a durable `OrderStatus`; the Kafka-visible ones — acceptance (`order-placed`), confirmation (`order-confirmed`, `notification-requested`), failure (`notification-requested`) and the refund trigger (`refund-requested`) — also write an `outbox_events` row in the same transaction, relayed by Debezium (ADR-011). The intermediate phases (`RESERVING`, `PAYMENT_PENDING`, `CONFIRMING_INVENTORY`, `COMPENSATING`) are durable in the database, not on Kafka; all but `RESERVING` are also in the pipeline gauges by age. `RESERVING` is left out by design (`reservation-confirmation.md`): no money has moved, and a hold that cannot complete is dead-lettered within seconds into a `DELIVERY_EXHAUSTED` incident, which is already gauged and alerted. *(Corrected 2026-10-07: the first version said every transition wrote an outbox row.)* Covered by `CheckoutPipelineIntegrationTest` and `RefundPipelineIntegrationTest`.
- [x] Shutdown behavior is demonstrated: readiness refuses new traffic, in-flight work drains or cancels cooperatively, and no duplicate processing occurs after restart.
      *Orders, 2026-10-08:* demonstrated on compose with the Kubernetes budget (`docker compose stop -t 35`: 45s grace minus the 10s preStop). Readiness turns `OUT_OF_SERVICE` at SIGTERM; each Kafka listener finishes the record in progress and stops (`immediate-stop`, added after the drill showed the default processing the whole polled batch); the rest is redelivered and resumed from durable state. Three drills — HTTP load, a 40-record backlog, a stop while Payments was unreachable — ended with every accepted order CONFIRMED, one payment authorization per order and nothing dead-lettered, checked across the three databases by `scripts/local-consistency-check.sh`. Semantics in [orders-shutdown-and-replay.md](operations/orders-shutdown-and-replay.md), evidence in [the shutdown report](operations/reports/2026-10-orders-shutdown-local.md). The Kubernetes procedures are the cluster half of #124.
- [x] The async design explains where suspending work reduces cost/scaling pressure and where it does not help CPU-bound work.
      *Orders, 2026-10-08:* ADR-007 (`design/architecture-decisions.md`) does exactly this: "Where Suspending Work Helps" (I/O-bound calls to Inventory and Payments, Kafka, asynchronous notifications) and "Where Async Does Not Help" (CPU-bound work, database consistency, retry safety, overload control), with the operational consequences — aligned on 2026-10-08 with the drain-not-cancel shutdown ([orders-shutdown-and-replay.md](operations/orders-shutdown-and-replay.md)).

## 2. Consistency and Idempotency

- [ ] Inventory uses the selected Strong CP / PC-EC consistency model.
- [ ] The "never oversell" invariant is implemented with atomic 10-minute seat holds or an equivalent reservation state machine.
- [x] Payment is attempted only after Inventory creates a valid hold.
      *Orders, 2026-09-18:* the pipeline runs `stageHold` → `stageAuthorize` → `stageConfirmInventory` in that order (`CheckoutOrchestrator`). `stageAuthorize` is reached only once `markPaymentPending` has committed the reservation id and expiry Inventory returned, so a refused or failed hold ends the pipeline before any Payments call is made.
- [ ] After payment authorization, Inventory atomically transitions the still-valid reservation `HELD -> CONFIRMED` and its seats `HELD -> SOLD` before Orders becomes `CONFIRMED`.
- [ ] Holds expire after 10 minutes or are released when payment fails/cancels.
- [ ] Concurrent confirmation, expiration, and release prove that exactly one transition wins and that confirmed/sold seats are never released by hold cleanup.
- [ ] Orders, Inventory, and Payments persist idempotency keys and return stable outcomes for duplicate requests/events.
- [x] Duplicate `order-placed` events and retried payment authorizations cannot double-reserve or double-charge.
      *Orders, 2026-10-08:* Orders dispatches every redelivery on the durable order state and calls with one key per order (`inv-{orderId}`, `pay-{orderId}`): `CheckoutPipelineIntegrationTest` — "a redelivered order-placed is idempotent and does not reprocess a terminal order", "a crash between the Inventory sale and the Orders commit converges without duplicate events". Inventory cannot store a second reservation for an order even after its idempotency record expires (`uk_reservations_order_id`; "reserveSeats should return 409 when orderId already exists in DB…"), and Payments replays a stored authorization for a repeated key ("authorize - returns cached response on idempotency match"). On compose, a 40-record backlog redelivered across a stop and a stop while Payments was unreachable left exactly one authorization per order ([shutdown report](operations/reports/2026-10-orders-shutdown-local.md)). **Limit:** Payments keeps idempotency records for 24h and `payment_authorizations` has no per-order uniqueness, so an order left in `PAYMENT_PENDING` for more than a day and then redelivered would be authorized again; today that is prevented upstream — the hold expires after 10 minutes and a redrive of a `PAYMENT_PENDING` order whose hold expired is refused ("a redrive is refused when the hold behind a PAYMENT_PENDING order has already expired").
- [ ] A delayed hold replay returns the reservation's current state and cannot return stale `HELD` or create a second reservation for the same order.

## 3. Resilience Engineering

- [x] Cross-service synchronous calls use Resilience4j circuit breakers with documented open/half-open settings and safe fallback behavior.
      *Orders, 2026-09-18:* `ResilienceConfiguration` declares four breakers (`inventory`, `payments`, `inventory-read`, `catalog-read`) with a 20s time-based window, `minimumNumberOfCalls=5`, 50% failure and slow-call thresholds, `waitDurationInOpenState=10s` and 3 half-open calls. Business refusals (`DefinitiveRefusal`, 400/404/409) are excluded so a decline storm cannot open a breaker. Fallback is an explicit unknown outcome that preserves the durable phase, never a speculative decision.
- [x] Bulkheads isolate checkout resources from unrelated traffic.
      *Orders, 2026-09-18:* separate 20-permit bulkheads per dependency in `ResilienceConfiguration`. Seat-map browsing (`inventory-read`) and offer lookup (`catalog-read`) have their own pools, so saturating either sheds that traffic and leaves the money-path permits untouched.
- [ ] Every remote call has timeout and bounded retry policy with backoff and jitter.
- [x] Overload is handled with controlled refusal or load shedding rather than unbounded queues.
      *Orders, 2026-09-18, corrected 2026-10-07:* `maxWaitDuration = ZERO` on every bulkhead — a saturated pool refuses immediately instead of queueing. On the synchronous paths (offer lookup at checkout entry, the seat map) that refusal, like an open breaker, is answered `503`, which tells the caller to ask again rather than reporting a business failure. Inside the pipeline the customer already holds a `202`, so a refusal is an unknown outcome: the durable phase is kept and the delivery is retried, and if retries are exhausted the incident is labelled `dependency_error` by `FailureClassifier.forDeadLetter`, which classifies the recoverer's cause header rather than the container's wrapper (`DeadLetterHeadersTest` runs it through the real `DeadLetterPublishingRecoverer`).
- [x] Unknown confirmation and payment outcomes preserve the durable Orders phase; exhausted Kafka retries create a reconciliation record and can be safely redriven.
      *Orders, 2026-09-18:* unknown outcomes leave the phase at `CONFIRMING_INVENTORY` / `PAYMENT_PENDING` rather than deciding; `OrderPlacedDeadLetterListener` and `RefundRequestedDeadLetterListener` open a `reconciliation_records` row without touching the phase, and `POST /api/orders/{orderId}/reconciliation/redrive` re-runs it. A payment reversal that Payments declined *definitively* after #122 (2026-10-07) is redrivable too: Payments no longer caches a declined refund, and moves its provider key after each recorded decline, so the redrive is a real attempt. *(Until then this item carried a known exception, #111.)*
- [x] Notifications can be killed while checkout still succeeds after reservation and payment.
      *Orders, 2026-10-08:* demonstrated on compose: with every Notifications instance stopped, 20/20 checkouts reached CONFIRMED and none failed because of Notifications. Orders makes no call to Notifications — `notification-requested` is an outbox row committed with the order's terminal state and relayed by Debezium — so stopped or killed makes no difference to checkout. On restart the consumer caught up the 20 retained events in ~4s. Evidence: [Notifications outage report](operations/reports/2026-10-notifications-outage-local.md).
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
- [x] A single order can be traced through gateway, Orders, Inventory, Payments, and Kafka stages.
      *Observability, 2026-10-13:* on compose, one checkout to `CONFIRMED` is **one trace of 36 spans**
      across Traefik and all five services, Kafka stages included as `<event> publish` / `<event> process`;
      a refund is one trace too. The span tree, and the two shapes worth knowing before reading one in
      Tempo, are in [slo-observability.md](operations/slo-observability.md) §Tracing. It took three
      changes: the gateway span from Traefik's OTLP tracing (#118), the pipeline's calls made children
      of the consumer span (#125), and the trace context carried across the outbox through a
      `traceparent` column and the Debezium header placement (#126). `CheckoutPipelineIntegrationTest`
      pins that a checkout's outbox rows share one trace id.
      **Limit — the evidence is local only.** The cluster has no trace backend and the chart ships
      `tracing.enabled: false` against an endpoint that does not exist (configuration #38), so this
      cannot be shown on AKS. Separately, the AKS Orders connector does not yet place the `traceparent`
      header the local one does, so even with a backend the Kafka hop would still start a new trace.
      Both are open and named in `slo-observability.md`.

## 5. Security and Configuration

- [ ] Traefik rejects unauthenticated requests to protected external APIs before they reach service pods.
- [ ] Services validate the authenticated principal and enforce resource ownership or privileged scopes locally.
- [x] Order reads are authorized by owner identity or by an explicit operational/admin role.
      *Orders, 2026-09-18:* `OrderController` resolves the principal from the JWT `sub` and answers `404`, not `403`, to a non-owner, so the API never reveals that another customer's order exists. The explicit exception is the `operations` role, and it is deliberately **not** widened on the history endpoint — there the principal filter is applied in SQL (`OrderRepository.findHistoryFirstPage`) so a later controller edit cannot bypass it. Covered by `OrderControllerOwnershipTest`.
- [ ] Inventory and Payments are internal service-to-service APIs, not public endpoints, and require service credentials plus propagated user context.
- [ ] The cluster/proof target uses Keycloak as the OIDC provider; local development may use a mock OIDC issuer or fixed development JWTs without changing production API contracts.
- [ ] Final cluster/demo proof does not depend on mocked services or fake in-memory substitutes for core infrastructure.
- [ ] TLS is terminated at Traefik in the cluster environment.
- [ ] Secrets are stored through SealedSecrets or an equivalent GitOps-safe mechanism.
- [x] Money-path audit logs include correlation ID, principal ID, order ID, and outcome, while excluding secrets and payment details.
      *Orders, 2026-10-08:* Orders coordinates the money path and writes one `audit` line per decision — checkout accepted/replayed/rejected, confirmed/failed, cancel, refund requested and settled, operator redrive and resolve — with `correlationId`, `principalId`, `orderId`, `outcome` (`MoneyPathAudit`; fields and vocabulary in [observability-conventions.md](design/observability-conventions.md#money-path-audit-log)). Only identifiers and closed-vocabulary values reach the line; a test asserts the payment token and email never do. Covered by `MoneyPathAuditTest`, `CheckoutOrchestratorTest`, `RefundOrchestratorTest`, and `CheckoutPipelineIntegrationTest` (one correlation id from the HTTP accept to the consumer's outcome). Compose proof: a checkout, a price-mismatch refusal and a refund produced five lines, every one with all four fields — the HTTP lines under the caller's `sub`, the pipeline lines under the owner and the client's `X-Correlation-Id` — and no token or email. Inventory and Payments act on Orders' calls under service credentials; their own logs are not audit lines.
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

- [x] `governance/agent-governance.md` documents credentials, permissions, blast radius, review gates, and worst-case failure.
      *Observability, 2026-10-13:* all five are now sections of their own. **Credentials** states what an
      agent may hold and why — the operator's own Git and GitHub identity so every commit and comment is
      attributable to a person, no cluster credentials, no secret material, and confirmation before every
      write — including the uncomfortable detail that the GitHub token carries write scope because the
      classic scopes have no read-only tier for private repositories, which makes the limit procedural
      rather than technical. **Worst-case failure** names the shortest path from a generated artifact to
      production (CI writes an image tag here, ArgoCD applies it with `prune` and `selfHeal`) and states
      the control. Allowed work, decisions agents must not own, permissions and the review checks were
      already there; three checks were added from cases 15-17.
- [x] `agent-log.md` contains at least three concrete agent mistakes and how the team detected and corrected them.
      *All slices, 2026-10-13:* seventeen cases, each with the agent output, why it was wrong, how it was
      caught and the correction. They are not all of one kind, which is the point: wrong framework APIs
      (5, 13), test harnesses that passed for the wrong reason (7, 14), design faults that would have
      reached production (1, 2, 3, 11, 12), an over-permissive ServiceAccount (4), and — added for this
      slice — an agent asserting a shared branch was broken from its own stale environment (15), one
      bypassing the project's task runner and then diagnosing the application for it (16), and one
      describing a checkout as "read-only" in the summary a human was asked to approve (17).
- [ ] Agent-generated delivery artifacts require human review or policy-as-code before merge to the configuration repository.
      *Observability, 2026-10-13:* **deliberately left unticked.** The rule is written — in
      `governance/agent-governance.md` and, for payments and inventory consistency, in
      `planning/team-agentic-implementation-plan.md` — and it is not practised. In the configuration
      repository, which this item is about, the last twelve merged pull requests (#36–#53) had zero
      reviews, and CI runs no policy checks on manifests. The application repository is no different: of
      the last ten merged pull requests, nine had none; #126, which added a column and a migration to the
      Orders outbox, merged with none. Ticking this on the strength of the documentation would be the one
      claim in this file that the repositories contradict. Branch protection cannot enforce it either: the
      organisation is on GitHub's free plan and both repositories are private, so branch protection,
      rulesets and required status checks are unavailable (the API answers 403). Until that changes, the
      gate can only be procedural — a human review before every merge to the configuration repository —
      and the honest statement is that it is documented, not enforced.

## 8. Final Deliverables

- [ ] `docs/` contains design/consistency justification, SLO definitions, chaos reports, postmortem, agent threat model, and agent log.
- [ ] A blameless postmortem is completed using `templates/postmortem-template.md`.
- [ ] A 5-minute recorded demo link is committed.
- [ ] The demo shows the running system, dashboards, canary, blue/green, one injected failure, and one alert firing.
