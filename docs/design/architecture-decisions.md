# Architecture decisions

These are the current design choices and their trade-offs. [API contracts](api-design.md) and [checkout lifecycle](reservation-confirmation.md) define executable behavior; [deployment](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/deployment.md) describes environment wiring. Stable ADR identifiers are preserved; editorial history lives in Git.

## ADR-001: Inventory consistency

Use strong PostgreSQL decisions for the finite seat resource, described as CP / PC-EC.

Protecting seat ownership takes priority over accepting writes during a partition. This can temporarily refuse checkout.

Alternative considered: Eventual reservation or an unlocked expiring hold would permit conflicting allocations and require customer compensation.

## ADR-002: Money-path interactions

Use synchronous Inventory/Payments decisions inside a durable asynchronous checkout.

Immediate decisions make finite-seat and money outcomes explicit; Kafka decouples processing and notifications. Orders still depends on those services to finish.

Alternative considered: Full choreography would require additional intermediate states and reconciliation for every decision.

## ADR-003: Progressive delivery

Use Orders canary and Catalog blue/green.

Gradual Orders exposure protects orchestration changes; Catalog can validate a parallel read revision and switch back quickly. Kafka processing exposure differs from HTTP weights.

Alternative considered: Inventory canary increases state-sensitive mixed-version complexity; Payments blue/green would not demonstrate progressive protection of the central orchestrator.

## ADR-004: Notification failure mode

Treat notification failure as graceful degradation.

Notification delivery happens after a business outcome. Checkout must not wait for the customer-message consumer. Notifications may be delayed and its current read model is best effort: an in-memory projection rebuilt from the retained `notification-requested` topic on each start (#131), not a durable store.

Alternative considered: Synchronous notification in checkout would turn a messaging outage into a purchase failure.

## ADR-005: Authentication and authorization

Use Keycloak identities and service-enforced authorization.

Customer ownership and operator actions need distinct identities; local test substitutions must not become the cluster security model. Gateway protection does not replace resource authorization.

Alternative considered: Anonymous checkout leaves ownership and audit boundaries unspecified.

## ADR-006: Payment provider integration

Keep the payment adapter on a real provider sandbox/test API.

This exposes realistic latency, provider idempotency and credential handling without live money or raw card storage. Unknown outcomes require explicit recovery.

Alternative considered: A pure internal mock omits provider failure modes; live charges introduce unnecessary financial and credential risk.

## ADR-007: Async cost and scaling

Use suspending Kotlin work for I/O-bound checkout while bounding resource use.

Suspension avoids holding a platform thread while remote I/O waits. CPU work still consumes CPU; consistency, idempotency and overload control require their own mechanisms. One Kafka record is the durable failure domain.

Alternative considered: Thread-per-request/blocking I/O increases waiting-thread pressure. A detached coroutine alone cannot recover work after process loss.

## ADR-008: Datastore ownership

Give Orders, Inventory and Payments separate logical PostgreSQL databases, credentials and migrations.

Service ownership prevents cross-service joins and mutations. A shared database server is acceptable for constrained environments but remains a common failure domain.

Alternative considered: A shared schema weakens isolation; separate schemas make accidental coupling easier; three separate database clusters add unnecessary operational overhead.

## ADR-009: Schema migrations

Run versioned Flyway migrations inside each service before serving requests.

JDBC is confined to bootstrap; runtime I/O remains R2DBC. Compose demo seed is repeatable and cluster seed uses an ordered bootstrap Job. Applied migrations are immutable.

Alternative considered: Unversioned schema population cannot manage incremental evolution; a separate migration Job adds artifact/ordering work; Liquibase adds capabilities not needed for plain PostgreSQL migrations.

## ADR-010: Shared wire types

Use the framework-free money-path-contracts Gradle module for shared wire types.

A jointly built monorepo can catch producer/consumer drift at compile time. Sharing messages does not authorize database sharing.

Alternative considered: Service-local DTOs drift; provider-specific libraries add release coordination; code generation or consumer-contract infrastructure adds more tooling than this project needs.

## ADR-011: Durable pipeline

Drive checkout from the order-placed consumer and dispatch on persisted state.

State and outbox commit together; replay resumes idempotent decisions. Shutdown finishes the current record and leaves unstarted records to Kafka. Consumer backoff blocks following work on that partition.

Alternative considered: A detached in-memory coroutine loses work on crash; full event choreography changes the synchronous decision boundaries.

## ADR-012: Service credentials

Orders obtains a Keycloak client-credentials token for internal Inventory and Payments calls.

Internal identity is distinct from customer ownership. Issuer/JWKS configuration must agree across public and internal URLs; credentials belong in managed secrets. Required customer context travels explicitly.

Alternative considered: Unauthenticated calls fail the internal boundary; forwarding user tokens requires a broader delegation/token-exchange model.

## ADR-013: Inventory confirmation

Confirm Inventory synchronously before committing final Orders confirmation.

The database lock decides confirmation against expiry. A committed sale can be replayed after a lost response; unknown outcomes stay resumable.

Alternative considered: Using order-confirmed as an Inventory command publishes success before the seat decision and makes correctness depend on consumer lag.

## ADR-014: Post-sale seat return

Return sold capacity through a dedicated, idempotent post-sale transition.

It preserves the rule that checkout release/expiry cannot unsell a ticket. Departed-run visibility is filtered on reads rather than coupling Inventory writes to schedules.

Alternative considered: Reusing hold release weakens the sold-seat invariant; never returning sold capacity wastes refunded inventory; schedule checks inside Inventory couple consistency to Catalog data.

## ADR-015: Commercial refund

Run commercial refunds as a separate durable process, money first and seat second.

Distinct refund events and payment-key namespace prevent checkout replay or compensation from impersonating a commercial reversal. Persisted offer terms avoid depending on mutable Catalog history. Unresolved money or seats stay visible.

Alternative considered: Reusing order-placed could restart a completed purchase; returning seats before money leaves the customer exposed; reusing the compensation key could replay the wrong provider result; terminal refund failure hides unfinished work.

## ADR-016: Customer hold and payment windows

Support an explicit customer hold followed by payment, while retaining direct checkout.

The customer gets a stable order and deadline before entering payment details. Inventory opens a one-time processing window so user reading time does not consume the execution budget. Backend rollout must finish before tokenless clients are enabled.

Alternative considered: Starting payment immediately does not support the held-seat interaction; extending on polls/activity would allow indefinite holds; 3-D Secure and post-payment customer action are outside the adopted reduced scope.

## Operational consequences

[Runtime conventions](../development/contributing.md), [instrumentation](../development/instrumentation.md), [shutdown](runtime-lifecycle.md) and [recovery](../operations/orders-recovery-runbook.md) apply these decisions. [SLO definitions](slo.md) retain the adopted targets, including the recovery objectives. No environmental test result changes these decisions implicitly.
