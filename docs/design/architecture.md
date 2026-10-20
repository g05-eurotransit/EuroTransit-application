# Microservice architecture

EuroTransit exposes a React storefront and the public Catalog, Orders and Notifications APIs through Traefik. Orders coordinates checkout using synchronous Inventory and Payments decisions inside a Kafka-driven workflow. Orders, Inventory, and Payments persist their own state in PostgreSQL. Catalog supports its own PostgreSQL database when enabled, with an in-memory compatibility default; the Notifications read model remains in process memory.

## System view

[![EuroTransit microservices: public APIs, synchronous checkout decisions, owned databases, and asynchronous outbox delivery](../diagrams/microservices/architecture.svg)](https://g05-eurotransit.github.io/EuroTransit-application/microservices/architecture.html)

[Open the full-size SVG](../diagrams/microservices/architecture.svg) · [Open interactive diagram](https://g05-eurotransit.github.io/EuroTransit-application/microservices/architecture.html)

The diagram shows runtime responsibilities and the main checkout interactions, including Orders' outbox relay. It describes repository code; it is not a live-cluster observation. Inventory and Payments also produce outbox events; their relay paths are omitted from this checkout-focused view. Physical placement belongs to the [deployment view](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/deployment.md).

## Responsibilities and interactions

| Component | Responsibility | State and dependencies |
| --- | --- | --- |
| React storefront | Browse offers, submit checkout, display order progress and notifications | Calls public APIs; signs in through Keycloak using code flow with PKCE |
| Catalog | Supply routes, offers, prices, and refund terms | PostgreSQL when explicitly enabled, in-memory compatibility default; read by the storefront, Orders, and Notifications |
| Orders | Resolve offers, accept orders, orchestrate checkout and post-sale refunds | Owns order, idempotency, reconciliation, and outbox records; calls Catalog, Inventory, and Payments |
| Inventory | Hold seats and perform sale, release, expiration, and post-sale return transitions | Owns seats, reservations, idempotency, and outbox records |
| Payments | Adapt authorization and reversal requests to the payment provider | Owns payment, refund, idempotency, and outbox records; calls Stripe's sandbox adapter |
| Notifications | Consume notification requests and expose customer history and SSE | In-memory read model; resolves optional trip context from Catalog |
| Kafka and Debezium Connect | Relay committed outbox events and deliver asynchronous work | Orders consumes `order-placed`; Notifications consumes `notification-requested` |

Checkout resolves the offer before acceptance and commits the order together with its initial outbox event. Kafka delivery drives the durable Orders pipeline. A tokenless checkout holds seats and waits for the customer; the owner payment command opens a single payment window before authorization. Direct checkout includes a token and proceeds without that waiting stage. Both paths confirm Inventory before committing confirmation and notification events. The [checkout lifecycle](reservation-confirmation.md) defines deadlines, transitions, unknown outcomes and compensation.

The [shared Gradle modules](../../settings.gradle.kts) provide wire types (`money-path-contracts`), security support, and observability code. They are libraries loaded by services and have no independent runtime endpoint.

## Boundaries and constraints

Services enforce JWT roles and resource ownership. Orders obtains a service token for internal Inventory and Payments calls. Traefik routing is an exposure boundary; it does not itself prove authentication enforcement. Kubernetes ingress and forward-auth settings are described in the [deployment view](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/deployment.md).

The three money-path services use separate logical databases and credentials. They coordinate through HTTP and events. [Catalog storage](catalog-storage.md) adds its own logical database and credentials when PostgreSQL mode is enabled. The in-memory default remains available for environments awaiting migration. The embedded architecture diagram records the earlier in-memory topology; use this storage reference for the optional PostgreSQL path. The [Notifications store](../../notifications/src/main/kotlin/it/polito/cpo/notification/NotificationStore.kt) still has no durable read-model storage. Notifications history and live fan-out are local to each process; Kafka retention alone does not establish durable customer history or cross-replica fan-out.

Notification consumption is asynchronous and does not participate in the confirmation transaction. The [Stripe adapter](../../payments/src/main/kotlin/it/polito/cpo/payments/service/provider/StripeSandboxProvider.kt) implements its authorization operation using `/charges`, with capture enabled by default. An unknown response can therefore follow a provider-side effect; payment recovery must follow the canonical protocol.

## Authoritative references

- [API and event contracts](api-design.md) define endpoints, event consumers and authorization.
- [Reservation confirmation](reservation-confirmation.md) owns checkout states, invariants, and compensation rules. [Architecture decisions](architecture-decisions.md) owns the rationale.
- [SLOs and SLIs](slo.md) define objectives; [observability conventions](../development/instrumentation.md) define instrumentation conventions.
- [Deployment](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/deployment.md) describes environment configuration, ingress, persistence topology, and operational limitations.
