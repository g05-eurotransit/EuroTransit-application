# Architecture Decisions

This document records the team-owned decisions required by the assignment specification. The selected options are intentionally explicit because these are judgment points, not implementation details to outsource to tooling.

## ADR-001: Inventory Consistency Model

**Decision:** Use Strong Consistency, modeled as CP under CAP and PC/EC under PACELC.

Orders owns the customer order state and the required PostgreSQL order database managed through CloudNativePG. Inventory separately owns the finite seat resource and the reservation/hold state needed to enforce the no-oversell invariant. A seat must never be sold twice, even if requests are duplicated, messages are replayed, or an Inventory pod dies mid-reservation.

### Selected Option: Strong CP / PC-EC

- Use PostgreSQL atomic updates or a reservation state machine as the Inventory source of truth for seat holds. This is separate from Orders' order-state database ownership.
- Seat selection in the UI is tentative and does not reserve inventory.
- Checkout creates a strongly consistent 10-minute hold only if the seat is available for the given travel date.
- Payment is attempted only after the hold succeeds.
- If payment is authorized and Inventory confirms the still-valid hold before it expires, the seat transitions to sold.
- If payment fails, the order is cancelled, or the 10-minute hold expires, the seat returns to available.
- Store idempotency keys for hold/reservation attempts so retries resolve the original reservation and return its current terminal or non-terminal result without extending the hold.
- During a database partition or primary failover, checkout writes may fail temporarily rather than accept inconsistent holds.

The intended state model is:

```text
AVAILABLE -> HELD -> SOLD
AVAILABLE -> HELD -> RELEASED/EXPIRED -> AVAILABLE
```

### Why This Is Better

This option protects the core business invariant. EuroTransit can tolerate a temporary checkout failure more easily than selling the same seat to two customers. It is also easier to prove under chaos testing: the expected invariant is binary and observable.

### Alternatives Considered

- **Eventual Saga reservation:** Accept orders asynchronously and compensate later if the same seat is assigned twice. This increases availability but violates the "never oversell" invariant temporarily and requires refund/cancellation flows that are harder to defend in the assignment proof.
- **Short-lived hold without strong consistency:** Create a hold with expiration but without a strongly consistent database transition. This still risks two active holds for the same seat under concurrency, so it does not protect the core invariant.

## ADR-002: Money Path Interaction Model

**Decision:** Use a hybrid sync+async checkout model.

### Selected Option: Hybrid Sync+Async

- `POST /api/orders` returns `202 Accepted` quickly with an `orderId`.
- Orders persists the accepted order and emits `order-placed`.
- Orders runs the checkout pipeline with Kotlin coroutines/flows.
- Inventory hold creation and final confirmation are synchronous idempotent decisions because seat consistency must be known before payment and before the order becomes final.
- Payment authorization is a synchronous idempotent decision because retries must not double-charge.
- Kafka carries durable pipeline events and decouples notifications from checkout success.

### Why This Is Better

This option matches the assignment's async requirement without pretending every decision can safely be deferred. The costly/slow parts are observable and recoverable through Kafka, while the three correctness boundaries—seat hold creation, payment authorization, and final Inventory confirmation—remain explicit synchronous decisions protected by resilience policies.

### Alternative Considered

- **Fully asynchronous checkout:** Orders would only publish events and all downstream work would happen through Kafka. This maximizes decoupling, but it makes immediate reservation/payment outcomes less clear and complicates the user-facing order state. It is harder to prove "no oversell" and "no double-charge" without building a more complex saga and reconciliation model.

## ADR-003: Progressive Delivery Mapping

**Decision:** Demonstrate canary on Orders and blue/green on Catalog.

### Selected Option: Orders Canary + Catalog Blue/Green

- **Orders canary:** Route a small traffic share, for example 5%, to the new Orders version. Promote only if checkout success rate, latency, error rate, and circuit-breaker metrics stay within the defined SLO guardrails.
- **Catalog blue/green:** Deploy the new Catalog version beside the old one, validate health and read endpoints, then switch traffic. Keep the previous version available for fast rollback.

### Why This Is Better

Orders is the critical money path, so changes must be exposed gradually and judged with production-like symptoms. Catalog is stateless and read-heavy, so it is a better fit for blue/green: both versions can coexist, validation is simple, and rollback is low risk.

### Alternative Considered

- **Inventory canary + Payments blue/green:** Inventory contains critical consistency logic, so a canary sounds attractive. However, canarying state-sensitive reservation behavior can make proof harder because two versions may compete over one invariant. Payments blue/green is reasonable, but it does not demonstrate progressive protection of the central orchestration path as clearly as Orders canary.

## ADR-004: Notification Failure Mode

**Decision:** Notification failure is graceful degradation.

Checkout is complete once the 10-minute seat hold is converted to sold, payment succeeds, and Orders records confirmation. Notifications consumes `order-confirmed` or `notification-requested` asynchronously. If Notifications is down, Kafka retains the event and checkout remains successful.

## ADR-005: Authentication and Security Scope

**Decision:** Treat EuroTransit as enterprise-grade. Authentication and authorization are part of the design, not optional polish.

### Selected Option: Authenticated Public APIs with Internal Service Trust

- Public traffic enters only through Traefik with TLS configured in the cluster configuration repository.
- Customer-facing APIs require an authenticated user identity. The cluster/proof target uses Keycloak as the OIDC identity provider. In local development only, this may be replaced by fixed development JWTs or a mock OIDC issuer, but the API shape must still carry a user principal.
- Checkout uses the authenticated customer identity as part of the order ownership and audit trail.
- Order reads are authorized by ownership, with separate operational/admin access for support use cases.
- Inventory and Payments remain internal APIs and are not exposed directly outside the cluster.
- Secrets are managed through SealedSecrets or equivalent GitOps-safe secret mechanisms.
- The payment service integrates with a provider sandbox/test API, such as Stripe test mode or PayPal Sandbox. It must not handle real card data or live charges.

### Why This Is Better

This keeps the project aligned with an enterprise-grade target while allowing a pragmatic implementation path. A mock or simplified identity provider can be used only for local development, while the cluster/proof target must use Keycloak and still define where identity is enforced and how authorization affects the money path.

### Alternative Considered

- **No authentication:** This would reduce implementation work, but it leaves a major enterprise concern unspecified and weakens the design of order ownership, auditability, and payment authorization.

## ADR-006: Payment Provider Integration

**Decision:** Implement Payments as an adapter around a real provider sandbox/test API rather than a pure internal mock.

### Selected Option: Sandbox Provider Adapter

- Use a test-mode payment provider, for example Stripe test mode or PayPal Sandbox.
- Keep provider-specific code inside the Payments service behind a narrow authorization adapter.
- Use sandbox credentials only, loaded from environment/configuration and represented as secrets in GitOps.
- Persist idempotency records before/around provider calls so retries do not create duplicate authorization effects.
- Store only provider references, authorization status, idempotency key, amount, currency, order ID, and audit metadata.
- Do not store raw card data, live credentials, bearer tokens, or provider secrets in application logs or source code.

### Why This Is Better

This gives the project a realistic external dependency, secret-management requirement, latency/failure mode, and audit trail without handling real money or card data. It also makes the Payments resilience and chaos tests meaningful because the service must isolate provider timeouts, declines, retries, and sandbox outages.

### Alternatives Considered

- **Pure internal payment mock:** Easy to implement, but too weak for an enterprise-grade target because it avoids provider credentials, external latency, provider idempotency behavior, and realistic failure handling.
- **Live payment integration:** More realistic, but inappropriate for the project because it introduces real financial risk, compliance concerns, and unnecessary secret exposure.

## ADR-007: Async Cost and Scaling Model

**Decision:** Use Kotlin coroutines/flows for I/O-bound and event-driven checkout work, while keeping correctness boundaries explicit and synchronous where an immediate business decision is required.

### Where Suspending Work Helps

- Orders can accept checkout quickly and run reservation/payment/confirmation stages without dedicating one platform thread to each in-flight order.
- HTTP calls from Orders to Inventory and Payments are I/O-bound. Suspending while waiting for those responses reduces blocked threads under latency or provider slowdown.
- Kafka publishing and consuming are I/O-bound and benefit from coroutine-based backpressure, structured cancellation, and bounded concurrency.
- Notification processing is naturally asynchronous because customer notification must not determine checkout success.
- Graceful shutdown is easier to reason about when the Orders pipeline has an explicit coroutine scope as a failure domain and can stop accepting new work while draining or cancelling in-flight tasks.

### Where Async Does Not Help

- Async does not reduce CPU cost for CPU-bound work such as cryptographic verification, serialization hot spots, compression, or large in-memory transformations. Those need efficient code, bounded worker pools, or horizontal scaling.
- Async does not remove the need for database consistency. Inventory hold creation remains a synchronous decision because the system must know whether a finite seat is held before payment proceeds.
- Async does not make retries safe by itself. Idempotency records are still required for Orders, Inventory, and Payments.
- Async does not replace overload control. The system still needs timeouts, circuit breakers, bulkheads, and load shedding so queues do not grow until the service collapses.

### Operational Consequences

- Bound coroutine concurrency for the checkout pipeline instead of allowing unbounded fan-out.
- Propagate cancellation on SIGTERM and flip readiness before draining.
- Track queue depth, coroutine failures, Kafka lag, and terminal order states so async work is observable.
- Treat blocking calls inside coroutine paths as defects unless they are explicitly isolated on a bounded dispatcher.

## ADR-008: Money-Path Datastore Ownership

**Decision:** Use separate logical PostgreSQL databases for Orders, Inventory, and Payments, with separate credentials and migrations.

### Selected Option: Separate Logical Databases

- Orders owns `orders_db`.
- Inventory owns `inventory_db`.
- Payments owns `payments_db`.
- The databases may run on the same PostgreSQL cluster in local/proof environments.
- Services must not read, join, or mutate another service's database directly.
- Cross-service coordination happens through the approved HTTP APIs and Kafka events.

### Why This Is Better

This gives the team clear service ownership, permission isolation, migration boundaries, and idempotency storage boundaries without requiring three separate PostgreSQL clusters for the project proof.

### Alternatives Considered

- **Separate schemas in one database:** simpler to set up, but weaker isolation and easier accidental cross-schema joins.
- **One shared schema:** fastest initially, but it breaks service ownership and makes the money-path consistency and idempotency boundaries harder to defend.
- **Three PostgreSQL clusters:** strongest infrastructure isolation, but too much operational overhead for the project proof.

## ADR-009: Schema Migrations via Embedded Flyway

**Decision:** Manage each service's schema with versioned Flyway migrations executed embedded in the service at startup (before it serves traffic). The application keeps using R2DBC at runtime; a blocking JDBC driver is added solely for Flyway and is used only during bootstrap.

### Selected Option: Embedded Flyway

- Versioned SQL migrations live with the code in `src/main/resources/db/migration` (`V1__init.sql`, `V2__…`), tracked in the `flyway_schema_history` table. Demo data is not assigned a synthetic high migration version: Docker Compose loads its idempotent repeatable script from `db/local-seed`, while Kubernetes runs an ordered bootstrap Job after Inventory has completed its schema migrations.
- Existing developer volumes that already recorded the retired V999/V1000 seed migrations must be recreated once (`docker compose down -v`) before starting the updated Inventory image. This is intentionally a development-only reset; versioned schema migrations are never repaired or reordered in place.
- Flyway runs during startup, before WebFlux accepts requests; there is no separate migration Job or init container.
- Connection parts are externalized as env vars (see the Database conventions), so Flyway (JDBC) and R2DBC (runtime) build their URLs from the same source.

### Why This Is Better

- Ordered, incremental, checksummed schema evolution — which an unversioned `schema.sql` cannot provide once tables must be altered.
- A single deployable artifact and the **same** migration mechanism locally and in the cluster.
- The blocking JDBC driver runs only at startup, off the request path, so it does not violate the "no blocking on event-loop threads" rule (ADR-005 / coroutine conventions); it is inert once WebFlux is serving.

### Alternatives Considered

- **R2DBC `ResourceDatabasePopulator` + `schema.sql`:** no versioning, ordering, or history; cannot apply incremental changes to an existing schema. (This was the initial approach; see `agent-log.md`.)
- **Flyway/Liquibase in a Kubernetes Job or init container:** keeps the blocking driver out of the app, but adds moving parts (image/ConfigMap delivery of SQL) and makes local and cluster use diverge.
- **Liquibase instead of Flyway:** native rollback and dialect abstraction, but heavier and unnecessary for a single Postgres dialect with plain SQL.

## ADR-010: Shared Money-Path Contracts Module

**Decision:** Introduce a `money-path-contracts` Gradle library module that holds the wire contracts shared between money-path services — inventory reservation DTOs, payment DTOs, and the Kafka event envelope — instead of duplicating them per service. This reverses the earlier preference for service-local DTOs.

### Selected Option: One shared contracts module

- Pure data types only (no Spring/framework dependencies), so any service can depend on it.
- orders adopts it now; inventory, payments, and notifications adopt the same types as they touch their code.
- Statuses are enums (`ReservationStatus`, `PaymentStatus`) rather than free strings.

### Why This Is Better

- Single source of truth for cross-service contracts, so producer and consumer cannot silently drift (the drift that hit the event envelope — see `agent-log.md`).
- In a monorepo built together, the compile-time coupling cost is low, and the pattern already exists (`security-support`, `observability`).
- Data-ownership isolation (ADR-008: each service owns its database) is unaffected: this shares the message on the wire, not databases or runtime data.

### Alternatives Considered

- **Service-local DTOs (previous approach):** maximal decoupling, but duplication and drift; weak justification in a monorepo.
- **Provider-published client library (e.g., `inventory-api`):** cleaner ownership, but one module per provider and consumers wait on provider releases.
- **OpenAPI/AsyncAPI codegen or consumer-driven contracts (Pact / Spring Cloud Contract):** stronger guarantees, but over-engineered for this project's scope and timeline.

### Ownership

The module is shared: changes go through a PR reviewed by the owner of any affected service, and adding it to `settings.gradle.kts` is a shared-build change announced to the team.

## ADR-011: Durable Checkout Pipeline via `order-placed` Consumer

**Decision:** Orders runs the checkout pipeline (reserve → pay → confirm/compensate) from a Kafka consumer of its own `order-placed` event, not from an in-process detached coroutine scope. The synchronous entry (`POST /api/orders`) persists the order as `ACCEPTED`, records the caller idempotency record, and publishes `order-placed`; the consumer then drives the pipeline. This refines ADR-007: the pipeline's failure domain and shutdown story move from "an explicit coroutine scope that drains on SIGTERM" to "at-least-once Kafka delivery with committed offsets".

### Why This Is Better

- **Durable/recoverable:** the previous detached `CoroutineScope(SupervisorJob + Dispatchers.Default)` lost in-flight orders on a hard crash (stuck forever in `RESERVING`/`PAYMENT_PENDING`). With Kafka, the offset is committed only after processing, so a crash is recovered by redelivery — satisfying the DoD "no duplicate processing after restart" and "in-flight work drains or cancels cooperatively" via the listener container's graceful stop.
- **No throwaway migration:** `order-placed` was already published; this adds the consumer side. It keeps the design's synchronous consistency boundary (ADR-001/ADR-002) — reserve and pay remain synchronous HTTP calls *inside* the consumer — while making the orchestration durable.
- **Idempotent under at-least-once:** processing resumes from the durable Orders phase; terminal orders are skipped, while `PAYMENT_PENDING`, `CONFIRMING_INVENTORY`, and `COMPENSATING` repeat only their idempotent remote operation. Downstream keys are derived from the immutable order id (`inv-<orderId>`, `pay-<orderId>`, `rel-<orderId>`, `refund-<orderId>`), so redelivery converges without duplicating business effects.
- **Correlation preserved:** the consumer seeds the event's `correlationId` into the Reactor context and MDC, so the pipeline's outbound calls carry `X-Correlation-Id` and logs stay grep-able end-to-end.

### Consequences

- Orders is now both producer and consumer of `order-placed` (a "pipeline observer" per `api-design.md`); consumer group `orders`.
- The payment method token (a sandbox reference, not card data) is persisted on the order so the async pipeline can rebuild the payment request, and is cleared once the order reaches a terminal state. It is **not** placed on any Kafka event.
- Synchronous calls to Inventory and Payments are wrapped with Resilience4j (timeout, bounded retry with backoff/jitter on transient faults only, circuit breaker, bulkhead) — see `ResilienceConfiguration`. A payment decline is a normal HTTP 200 result, so it never trips the breaker.
- `order-placed` is persisted through the Orders transactional outbox and relayed by Debezium, closing the order-commit/event-publish gap. Exhausted consumer retries publish to `order-placed.DLT`; reconciliation preserves and redrives the durable Orders phase.

### Alternatives Considered

- **In-memory coroutine pipeline + cooperative drain (`@PreDestroy`) (previous direction):** simplest, but only survives *graceful* shutdown; a crash still loses in-flight work.
- **Full event choreography (a topic + cross-service consumer per stage):** maximal decoupling, but contradicts the synchronous consistency boundary for reserve/pay and is over-engineered for this scope.

## ADR-012: Service-to-Service Authentication via Client Credentials

**Decision:** Orders authenticates its outbound calls to Inventory and Payments with an OAuth2
**client-credentials** token from Keycloak, carrying the `service` realm role (→ `ROLE_service`, which
`/api/inventory/**` and `/api/payments/**` require). A dedicated confidential client `orders-service`
with a service account holds the role; orders attaches the token as a Bearer via a
`ServerOAuth2AuthorizedClientExchangeFilterFunction` on its WebClients. This implements the "service
credential" half of the internal-call contract in `api-design.md` and the "Internal Service Trust" of
ADR-005.

### Why This Is Better

- Client-credentials is the standard machine-to-machine grant; the token is obtained, cached and
  refreshed by the Spring `AuthorizedClientManager`. The **service-context** manager
  (`AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager`) is used, not the request-scoped one,
  because the checkout pipeline runs in a Kafka consumer with no `ServerWebExchange`.
- One `orders-service` client (one `ROLE_service`) covers both downstreams.
- Composes with the existing correlation propagation and Resilience4j on the WebClients (the clients now
  use the auto-configured `WebClient.Builder`, which also gives client-side metrics/tracing).

### Consequences

- **Issuer consistency**: the token is fetched over the in-network Keycloak URL, so `iss` must match
  what the resource servers validate. In local/compose this is pinned with `KC_HOSTNAME_URL` so `iss`
  is always `http://localhost:8081/realms/eurotransit` regardless of host. The cluster must configure
  the equivalent fixed frontend URL.
- **Secrets**: the `orders-service` client secret is a local dev value in the realm import; the cluster
  must supply it via a SealedSecret (`ORDERS_SERVICE_CLIENT_SECRET`) plus the internal
  `ORDERS_SERVICE_TOKEN_URI`.
- **Follow-up — propagated user context**: `api-design.md` asks for "service credential **plus**
  propagated user context". This ADR delivers the service credential; with a client-credentials token
  the downstream sees the *service account* as principal, not the customer. Propagating the end-user
  identity (e.g. an `X-User-Id` header, or a principal field the downstream reads for audit) is a
  documented follow-up requiring a coordinated contract change with Inventory/Payments. Order ownership
  is still enforced at Orders.

### Alternatives Considered

- **No outbound auth (the current gap):** simplest, but Inventory/Payments require `ROLE_service` → every
  real call returns 401 and the money-path breaks.
- **Propagate the end-user token (token exchange / on-behalf-of):** carries the real user context, but
  needs Keycloak token-exchange and a broader contract; heavier than needed now. Kept as the direction
  for the user-context follow-up.

## ADR-013: Synchronous Inventory Confirmation Before Final Order Confirmation

**Decision:** After payment authorization, Orders synchronously and idempotently asks Inventory to
transition the reservation from `HELD` to `CONFIRMED` and its seats from `HELD` to `SOLD`. Only after
Inventory accepts that transition may Orders mark the order `CONFIRMED` and publish final outbox
events.

Inventory serializes confirmation against expiration and release in its own database. Exactly one
transition may win: confirmation before the deadline or expiration after it. A retry of a completed
confirmation returns success; an expired or cancelled reservation cannot be resurrected.

Kafka remains the durable trigger for the checkout pipeline and the channel for facts and asynchronous
side effects. `order-confirmed` is a final fact for Notifications and observability consumers, not a
command that Inventory uses to decide whether seats can be sold.

This does not create a distributed ACID transaction. Unknown HTTP outcomes preserve the durable Orders
phase and are retried or reconciled through idempotent Payment and Inventory operations. Normal hold
release and checkout compensation never reverse a confirmed reservation; post-sale cancellation is a
separate business process.

The complete protocol, failure matrix, API shape, and acceptance criteria are documented in
[reservation-confirmation.md](reservation-confirmation.md).

### Alternative Considered

- **Confirm Inventory by consuming `order-confirmed`:** this makes checkout correctness depend on Kafka
  consumer lag relative to a hard ten-minute hold deadline and publishes an order-complete fact before
  Inventory has accepted the sale. A fully asynchronous confirmation saga is possible, but it requires
  additional intermediate states, response events, timeouts, deduplication, and reconciliation without
  a clear benefit for the current money path.

## ADR-014: Post-Sale Seat Refund Transition

**Decision:** Allow a dedicated post-sale transition that returns `SOLD` seats to `AVAILABLE` status upon order cancellation/refund, while strictly preserving Invariant 5 for checkout compensation.

`reservation-confirmation.md` originally excluded returning a confirmed seat to available, treating `SOLD` as terminal to protect against checkout compensation bugs. However, the business requires refunded tickets to be resold.

### Selected Option: Dedicated Post-Sale Transition

- Introduce a new reservation state transition (e.g., `CONFIRMED -> REFUNDED`) and seat transition (`SOLD -> AVAILABLE`) triggered by a distinct, dedicated internal operation.
- This operation must use the same reservation-row concurrency lock as confirmation, release, and expiration.
- The operation must be strictly idempotent by reservation and order.
- The existing `release` (used for checkout compensation) and `expiration` operations must **remain completely unable to modify a `CONFIRMED` reservation or a `SOLD` seat** (maintaining Invariant 5).
- Seats of a departed train will theoretically return to `AVAILABLE` in the database, but must be filtered out at read-time by the availability endpoint rather than blocked during the transition, separating data consistency from business visibility.

### Why This Is Better

This fulfills the business requirement of reselling refunded tickets without compromising the safety of the critical checkout money path. By creating a completely separate API and state transition for post-sale refunds, we guarantee that a delayed or erroneous checkout compensation (`release`) cannot accidentally un-sell a confirmed ticket.
