# EuroTransit API and Service Design

This document defines the service boundaries and communication contracts for EuroTransit. The design intentionally separates synchronous decisions that must be made during checkout from asynchronous work that can be retried, observed, and recovered through Kafka.

For the rationale behind the selected consistency, money-path, and delivery decisions, see [architecture-decisions.md](architecture-decisions.md).

## Security Boundary

EuroTransit is designed as an enterprise-grade system. Public APIs must assume an authenticated user identity. Local development may use fixed development JWTs or a mock OIDC issuer, but the cluster/proof target uses Keycloak as the OIDC identity provider.

- Traefik is the only north-south entry point and terminates TLS in the cluster environment.
- Public APIs expect OAuth2/OIDC bearer JWTs. Tokens must carry a stable subject (`sub`), tenant or organization context when applicable, and role/scope claims.
- Services treat the JWT `sub` claim as the authoritative `principalId`. The `email` claim is optional metadata for display or delivery and must not be used as the ownership key.
- The production-like cluster target uses Keycloak for OIDC. Mock identity providers and fixed JWTs are local-development substitutes only.
- Customer-facing write APIs, especially checkout, require an authenticated customer identity.
- `GET /api/orders/{orderId}` must only return orders owned by the authenticated customer, unless the caller has an operational/admin role. Customer-to-customer access returns `404 Not Found` so the API does not reveal that another customer's order exists.
- Inventory and Payments APIs are internal service-to-service APIs and are not exposed directly to public clients.
- Service-to-service requests must propagate correlation IDs, the authenticated principal where required, and a trusted service identity. The production target should use mTLS or an equivalent workload-identity control.
- Gateway authentication is not a substitute for service-level authorization. Each service must enforce local ownership, role, and scope decisions for resources it owns.
- Money-path actions must emit audit-ready logs with correlation ID, principal ID, order ID, and outcome. Logs must not contain bearer tokens, secrets, raw payment data, or card details.
- Secrets such as database credentials, signing keys, and payment-provider credentials belong in the configuration repository as SealedSecrets, not in application source.

### Endpoint Security Summary

| Endpoint Group | Exposure | Authentication | Authorization |
| --- | --- | --- | --- |
| `GET /api/catalog/**` | Public or authenticated depending on offer visibility | Optional for public catalog; required for personalized offers | Public read or customer scope |
| `POST /api/orders` | External | Required | Customer scope; principal becomes order owner |
| `GET /api/orders/{orderId}` | External | Required | Order owner or privileged operations scope |
| `POST /api/orders/{orderId}/reconciliation/redrive` | Internal operations | Required operations credential | Operations role only; redrives an open reconciliation record through the Orders outbox |
| `POST /api/orders/{orderId}/reconciliation/{recordId}/resolve` | Internal operations | Required operations credential | Operations role only; records evidence-backed resolution of a terminal invariant incident |
| `POST /api/inventory/reservations` | Internal | Required service credential plus propagated user context | Orders service only; order owner context required |
| `PUT /api/inventory/reservations/{reservationId}/confirmation` | Internal | Required service credential plus propagated user context | Orders service only; confirms the reservation for its owning order |
| `DELETE /api/inventory/reservations/{reservationId}` | Internal | Required service credential plus propagated user context | Orders service only; compensation for owned order |
| `POST /api/payments/authorize` | Internal | Required service credential plus propagated user context | Orders service only; payment belongs to owned order |
| `POST /api/payments/refund` | Internal | Required service credential plus propagated user context | Orders service only; idempotently reverses payment for a failed checkout |
| `GET /api/notifications` | External | Required | Authenticated customer can read only their own notifications |
| `GET /api/notifications/stream` | External | Required | Authenticated customer can receive only their own live notification events |

## Shared Request Headers

| Header | Required On | Contract |
| --- | --- | --- |
| `Authorization` | Protected external APIs and internal service calls according to the endpoint security summary | Carries a bearer JWT for public APIs or service identity for internal calls. |
| `X-User-Id` | Internal service-to-service calls (e.g. `POST /api/inventory/reservations`) | Explicitly propagates the original customer identity when the `Authorization` header carries a service account token instead of the user token. |
| `X-Correlation-Id` | All internal calls, logs, and Kafka events | Public clients may provide it; the gateway generates one if absent; services must propagate it unchanged through one checkout flow. |
| `Idempotency-Key` | `POST /api/orders`, both `POST /api/orders/{orderId}/reconciliation/**` operations, `POST /api/inventory/reservations`, `POST /api/payments/authorize`, `POST /api/payments/refund` | Identifies one mutating attempt. It is not an authorization credential and must be scoped with caller/user context, operation, and request fingerprint. |

Idempotency keys are generated at the caller boundary for the operation they protect:

- Public clients generate a new high-entropy `Idempotency-Key` before calling `POST /api/orders`.
- Public clients reuse that key only for retries of the same checkout attempt.
- A new checkout attempt uses a new key, even if the request payload is identical.
- Orders consumes the public checkout key and stores it with `principalId`, operation, request fingerprint, and result.
- Orders generates separate downstream keys for internal operations, derived from the immutable order id. The scheme is `inv-{orderId}` for the seat reservation, `pay-{orderId}` for payment authorization, `rel-{orderId}` for hold release, and `refund-{orderId}` for payment reversal.
- Inventory and Payments store their received keys scoped by operation, service caller/user context where applicable, request fingerprint, and result.
- Reusing the same key with the same logical payload returns the current idempotent business result.
- Reusing the same key with a different logical payload returns `409 Conflict`.

Idempotency retention is service-specific:

| Service | Operation | Retention |
| --- | --- | --- |
| Orders | Checkout/order placement | 24 hours |
| Inventory | Seat reservation | 30 minutes |
| Payments | Payment authorization | 24 hours |

Inventory seat holds last 10 minutes. The 30-minute idempotency record identifies the original reservation but does not freeze a cached `HELD` response, extend the deadline, or create a replacement hold. A replay resolves the reservation's current state. `reservations.order_id` remains unique after idempotency-record purge, so one order cannot acquire a second reservation.

## Service Boundaries

| Service | Responsibility | Interaction Style | Why this boundary exists |
| --- | --- | --- | --- |
| Catalog | Exposes routes, schedules, products, and offers. | Synchronous read API. | Catalog is read-heavy and tolerant of stale data, so it can be scaled and deployed independently from checkout. |
| Orders | Owns the customer-facing purchase workflow, order state, idempotent checkout acceptance, and its PostgreSQL database. | Synchronous entry API plus asynchronous pipeline orchestration. | Orders is the money-path coordinator: it accepts checkout requests quickly and records/reports order progress. |
| Inventory | Owns finite seat availability, time-limited seat holds, and the hold-to-sale transition. | Synchronous hold and confirmation decisions plus reservation events. | Inventory is the contended resource. It must create one strongly consistent hold before payment and atomically confirm the sale before Orders becomes final. Its reservation store is separate from Orders' order-state ownership. |
| Payments | Owns payment authorization state and integrates with a provider sandbox/test API. | Synchronous idempotent authorization plus payment events. | Payment authorization must not double-charge under retries; an immediate success/failure decision is needed before order confirmation. |
| Notifications | Sends confirmations and customer updates. | Fully asynchronous event consumer. | Notification failure must not fail checkout. Kafka buffers the work until the service recovers. |

## Datastore Ownership

Orders, Inventory, and Payments use separate logical PostgreSQL databases with separate credentials:

- Orders owns `orders_db`.
- Inventory owns `inventory_db`.
- Payments owns `payments_db`.
- Each service owns its own tables, migrations, idempotency records, and credentials.
- Services must not read, join, or mutate another service's database directly.
- Cross-service coordination happens through the approved HTTP APIs and Kafka events.
- A shared PostgreSQL cluster is acceptable for local/proof environments, but logical databases and credentials remain separate.

## Public APIs

### Catalog

- `GET /api/catalog/routes`
  - Lists available routes and schedules. Public data may be served without authentication; personalized pricing requires an authenticated user.
- `GET /api/catalog/routes/{routeId}`
  - Returns details for one route.
- `GET /api/catalog/offers`
  - Lists currently available pricing and ticket offers. Personalized offers require a customer principal.

### Orders

- `POST /api/orders`
  - Accepts a checkout request.
  - Requires an authenticated customer identity.
  - Returns `202 Accepted` with an `orderId` and initial status after the request has been accepted for processing.
  - Requires a public-client-generated `Idempotency-Key` so client retries do not create duplicate orders.
  - Stores checkout idempotency records for 24 hours.
- `GET /api/orders/{orderId}`
  - Returns the current order status: accepted, reserving, payment-pending, confirming-inventory, compensating, confirmed, failed, or cancelled.
  - Requires the authenticated customer to own the order, unless an operator/admin role is used.
  - Returns `404 Not Found` for customer-to-customer access to avoid revealing another customer's order.
- `POST /api/orders/{orderId}/cancel`
  - Cancels an order that has not yet started payment authorization, releasing any seat hold.
  - Requires the authenticated customer to own the order, or an operations role.
  - Accepted only while the order is `accepted` or `reserving`. From `payment-pending` onward it
    returns `409 ORDER_NOT_CANCELLABLE`, which is what closes the cancellation race before money
    moves; post-sale cancellation is a separate business process and cannot use hold release.
- Orders persists order state in `orders_db`.
- `POST /api/orders/{orderId}/reconciliation/redrive`
  - Requires an operations credential and an idempotency key. A service credential is refused with
    `403`: it drives automated internal calls and must not be able to redrive a customer's checkout.
  - Accepts only orders with an open reconciliation record, otherwise
    `409 NO_OPEN_RECONCILIATION_RECORD`.
  - Atomically stores the redrive result and writes a new `order-placed` outbox row, then returns `202 Accepted`.
  - A terminal order returns `409 ORDER_ALREADY_TERMINAL` and closes any stale record as it refuses.
  - Never modifies Inventory, Payments, or terminal Orders state directly. The stored order status is
    what selects the resume action, so the endpoint holds no knowledge of which stage resumes.
- `POST /api/orders/{orderId}/reconciliation/{recordId}/resolve`
  - Requires an operations credential, an idempotency key, a terminal order, and an evidence reference.
  - Resolves only an investigated invariant-violation record; it cannot waive payment reversal or change order state.
  - Refusals: `400 EVIDENCE_REFERENCE_REQUIRED`, `409 ORDER_NOT_TERMINAL`,
    `404 RECONCILIATION_RECORD_NOT_FOUND` (also when the record belongs to another order, so the API
    never reveals that one exists elsewhere), `409 RECONCILIATION_KIND_NOT_RESOLVABLE` for a kind that
    closes automatically, and `409 RECONCILIATION_ALREADY_RESOLVED`.

### Inventory

- `POST /api/inventory/reservations`
  - Attempts to create a 10-minute hold for the requested seats.
  - Internal API; callers must be trusted services such as Orders.
  - Requires an Orders-generated idempotency key derived from the order id, operation, and attempt.
  - Uses a strong consistency decision: the same seat cannot have two active holds.
  - Stores reservation idempotency records for 30 minutes without extending the 10-minute hold.
  - Owns reservation/seat availability state for the no-oversell invariant; this does not replace Orders' ownership of the order-state database.
- `PUT /api/inventory/reservations/{reservationId}/confirmation`
  - Confirms a non-expired hold for the owning `orderId` after payment authorization.
  - Atomically transitions the reservation `HELD -> CONFIRMED` and its seats `HELD -> SOLD`.
  - Is idempotent by reservation and order: a first success and every valid replay return `200 CONFIRMED`.
  - Serializes with expiration and release. If expiration wins, confirmation returns a stable conflict and cannot resurrect the reservation.
  - Uses the protocol and failure semantics defined in [reservation-confirmation.md](reservation-confirmation.md).
- `DELETE /api/inventory/reservations/{reservationId}`
  - Releases a hold when payment fails, the order is cancelled, or the 10-minute hold expires.
  - Is idempotent by reservation state, operates only on `HELD`, never releases `CONFIRMED`/`SOLD`, and does not require an `Idempotency-Key`.

### Payments

- `POST /api/payments/authorize`
  - Authorizes payment for one order.
  - Internal API; callers must be trusted services such as Orders.
  - Requires an Orders-generated idempotency key so retries return the original authorization result instead of charging again.
  - Stores payment authorization idempotency records for 24 hours.
  - Calls a configured provider sandbox/test API through a Payments-owned adapter; raw card data and live charges are out of scope.
  - Accepts only payment method tokens or provider references, never raw card number, CVV, or live payment credentials.
- `POST /api/payments/refund`
  - Fully reverses a previously authorized payment for one failed checkout.
  - Internal API; callers must be trusted services such as Orders (compensation when an order is cancelled or fails after authorization).
  - Requires an Orders-generated idempotency key so retries return the original refund result instead of refunding again.
  - The Payments-owned adapter selects a provider void for uncaptured funds or a provider refund after capture; Orders uses the same EuroTransit refund command in both cases.

### Notifications

- The service consumes events and sends or records email/SMS confirmations and failure updates.
- `GET /api/notifications`
  - Returns the authenticated customer's notification history.
  - Requires a normal bearer JWT and only returns notifications owned by the authenticated principal.
- `GET /api/notifications/stream`
  - Exposes live notification updates to the frontend using Server-Sent Events with `text/event-stream`.
  - Sends only notifications owned by the authenticated principal.
  - Is an enhancement over the stored notification history; if the stream disconnects, the frontend can fall back to `GET /api/notifications`.

## Kafka Events

Events use a common envelope. `schemaVersion` is the version of the event schema for the given `eventType`, not an order version or service version.

> Implementation note (ADR-011): Orders drives the money path by calling Inventory and Payments **synchronously over HTTP inside the `order-placed` consumer**, and reacts to their HTTP responses. It does not currently *consume* the `inventory-*`/`payment-*` topics; those events are emitted by Inventory/Payments for observability/audit and future event-driven consumers. The consumer column below is the target contract, not a statement that every consumer is wired today.

```json
{
  "eventId": "evt_123",
  "eventType": "order-confirmed",
  "schemaVersion": 1,
  "occurredAt": "2026-07-04T12:00:00Z",
  "correlationId": "corr_123",
  "orderId": "ord_123",
  "principalId": "customer_789",
  "payload": {}
}
```

Consumers must be idempotent because Kafka delivery is treated as at-least-once. Events must not contain bearer tokens, provider secrets, raw payment data, card details, or plaintext credentials.

| Event | Producer | Consumers | Purpose |
| --- | --- | --- | --- |
| `order-placed` | Orders | Orders pipeline observers, optional analytics | Durable record that checkout was accepted. |
| `inventory-reserved` | Inventory | Orders | Indicates that the requested seats are held for 10 minutes and can proceed to payment. |
| `inventory-failed` | Inventory | Orders | Indicates that the hold failed and the order must not proceed to payment. |
| `payment-authorized` | Payments | Orders | Indicates payment authorization succeeded. |
| `payment-declined` | Payments | Orders | Indicates payment failed and compensation may be needed. |
| `payment-refunded` | Payments | Orders | Indicates a refund succeeded (compensation completed after a prior authorization). |
| `payment-refund-failed` | Payments | Orders | Indicates a refund attempt failed and may need manual reconciliation. |
| `order-confirmed` | Orders | Notifications, observability consumers | Indicates the order is complete and customer notification can be sent. |
| `notification-requested` | Orders | Notifications | Optional explicit notification command if separated from `order-confirmed`. |

## Error Model

All services use a shared HTTP status mapping:

| Status | Meaning |
| --- | --- |
| `400` | Malformed request or required header missing |
| `401` | Missing or invalid authentication |
| `403` | Authenticated but not authorized |
| `404` | Resource missing or intentionally not visible |
| `409` | Idempotency conflict, reservation conflict, or incompatible state transition |
| `422` | Valid request shape but invalid business action |
| `503` | Dependency unavailable, provider unavailable, or circuit breaker open |

Error responses use structured JSON:

```json
{
  "code": "IDEMPOTENCY_CONFLICT",
  "message": "Idempotency key was reused with a different request",
  "correlationId": "corr_123"
}
```

Validation errors may add a machine-readable `details` object, but `code`, `message`, and `correlationId` remain mandatory.

## Money Path

The selected money path is hybrid sync+async:

1. Client submits `POST /api/orders` with a public-client-generated `Idempotency-Key`.
2. Orders validates the request, stores an accepted order, emits `order-placed`, and returns `202 Accepted`.
3. Orders executes the reservation/payment pipeline with Kotlin coroutines/flows and structured cancellation.
4. Orders calls Inventory synchronously with an Orders-generated reservation idempotency key to create a 10-minute seat hold because this is the consistency boundary.
5. Orders stores `reservationId`, transitions to `PAYMENT_PENDING`, and closes customer cancellation before authorization begins.
6. Orders calls Payments synchronously with `pay-{orderId}` only after the hold succeeds.
7. After authorization, Orders transitions to `CONFIRMING_INVENTORY` and synchronously confirms the reservation in Inventory. Inventory atomically transitions the reservation `HELD -> CONFIRMED` and its seats `HELD -> SOLD`, serialized against cleanup and release.
8. Orders marks the order `CONFIRMED` and records final outbox events only after Inventory confirms the sale.
9. Kafka publishes facts such as `order-confirmed` and asynchronous work such as `notification-requested`.
10. Notifications consumes events asynchronously; if it is down, checkout remains successful and Kafka retains the work.

## Resilience Requirements

- All synchronous service calls must have timeouts, bounded retries with backoff/jitter, circuit breakers, and bulkheads.
- Orders must flip readiness to refuse new traffic during shutdown while in-flight coroutine work drains or is cancelled cooperatively.
- Inventory and Payments must persist idempotency records so duplicate events or HTTP retries return the same outcome.
- Orders persists checkout idempotency records for 24 hours, Inventory persists reservation idempotency records for 30 minutes, and Payments persists authorization idempotency records for 24 hours.
- Inventory holds must expire after 10 minutes if payment does not complete, returning the seat to available state. Inventory idempotency retention must not renew an expired hold.
- Reservation confirmation, expiration, and release must use atomic state transitions. Unknown confirmation outcomes are retried or reconciled; they are not treated as definitive failures.
- Kafka retry exhaustion publishes to `order-placed.DLT`; a DLT consumer creates a reconciliation record without changing the durable checkout phase or issuing speculative compensation. Redrive uses the transactional outbox and resumes from the stored order state.
- Notification failure is a degraded mode, not a checkout failure.
- The checkout SLOs, alerts, and tracing requirements are defined in [slo-observability.md](../operations/slo-observability.md).
