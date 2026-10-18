# Microservice architecture

EuroTransit exposes a React storefront and five service APIs through Traefik. Orders coordinates checkout using synchronous Inventory and Payments decisions inside a Kafka-driven workflow. Orders, Inventory, and Payments persist their own state in PostgreSQL; Catalog data and the Notifications read model are held in process memory.

## System view

![EuroTransit microservices: public APIs, synchronous checkout decisions, owned databases, and asynchronous outbox delivery](../diagrams/microservices/architecture.svg)

[Open the full-size SVG](../diagrams/microservices/architecture.svg) · [Download the interactive HTML](../diagrams/microservices/architecture.html)

The diagram shows runtime responsibilities and the main checkout interactions, including Orders' outbox relay. It is based on repository code, rather than an observation of a running cluster. Inventory and Payments also produce outbox events; their relay paths are omitted from this checkout-focused view. Physical placement belongs to the [deployment view](../../../EuroTransit-configuration/docs/platform/deployment.md).

## Responsibilities and interactions

| Component | Responsibility | State and dependencies |
| --- | --- | --- |
| React storefront | Browse offers, submit checkout, display order progress and notifications | Calls public APIs; signs in through Keycloak using code flow with PKCE |
| Catalog | Supply routes, offers, prices, and refund terms | Seeded in-memory repository; read by the storefront, Orders, and Notifications |
| Orders | Resolve offers, accept orders, orchestrate checkout and post-sale refunds | Owns order, idempotency, reconciliation, and outbox records; calls Catalog, Inventory, and Payments |
| Inventory | Hold seats and perform sale, release, expiration, and post-sale return transitions | Owns seats, reservations, idempotency, and outbox records |
| Payments | Adapt authorization and reversal requests to the payment provider | Owns payment, refund, idempotency, and outbox records; calls Stripe's sandbox adapter |
| Notifications | Consume notification requests and expose customer history and SSE | In-memory read model; resolves optional trip context from Catalog |
| Kafka and Debezium Connect | Relay committed outbox events and deliver asynchronous work | Orders consumes `order-placed`; Notifications consumes `notification-requested` |

Checkout resolves the offer before acceptance and commits the order together with its initial outbox event. Kafka delivery drives the durable Orders pipeline. That pipeline requests a hold, authorizes payment, and confirms Inventory before committing confirmation and notification events. The precise transitions, unknown-outcome rules, and compensation behavior are defined in the [reservation protocol](reservation-confirmation.md).

The [shared Gradle modules](../../settings.gradle.kts) provide wire types (`money-path-contracts`), security support, and observability code. They are libraries loaded by services and have no independent runtime endpoint.

## Boundaries and constraints

Services enforce JWT roles and resource ownership. Orders obtains a service token for internal Inventory and Payments calls. Traefik routing is an exposure boundary; it does not itself prove authentication enforcement. Kubernetes ingress and forward-auth settings are described in the [deployment view](../../../EuroTransit-configuration/docs/platform/deployment.md).

The three money-path services use separate logical databases and credentials. They coordinate through HTTP and events. Catalog's [seeded repository](../../catalog/src/main/kotlin/it/polito/cpo/repository/InMemoryCatalogRepository.kt) and the [Notifications store](../../notifications/src/main/kotlin/it/polito/cpo/notification/NotificationStore.kt) have no durable read-model storage in this implementation. Notifications history and live fan-out are local to each process; Kafka retention alone does not establish durable customer history or cross-replica fan-out.

Notification consumption is asynchronous and does not participate in the confirmation transaction. The [Stripe adapter](../../payments/src/main/kotlin/it/polito/cpo/payments/service/provider/StripeSandboxProvider.kt) implements its authorization operation using `/charges`, with capture enabled by default. An unknown response can therefore follow a provider-side effect; payment recovery must follow the canonical protocol.

## Authoritative references

- [API and event contracts](api-design.md) define the wire surface. Its checkout request description contains both optional and required wording for `offerId`; the [checkout implementation](../../orders/src/main/kotlin/it/polito/cpo/service/CheckoutOrchestrator.kt) requires it. Resolve that contradiction in the later specification revision.
- [Reservation confirmation](reservation-confirmation.md) owns checkout states, invariants, and compensation rules. [Architecture decisions](architecture-decisions.md) owns the rationale.
- [SLOs and SLIs](../operations/slo-observability.md) define objectives; [observability conventions](observability-conventions.md) define instrumentation conventions.
- [Deployment](../../../EuroTransit-configuration/docs/platform/deployment.md) describes environment configuration, ingress, persistence topology, and operational limitations.
