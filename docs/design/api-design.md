# API and event contracts

EuroTransit exposes Catalog, Orders and customer Notifications through the public gateway. Inventory and Payments are internal APIs. This document owns wire behavior and authorization; [checkout lifecycle](reservation-confirmation.md) owns states, deadlines and recovery decisions.

## Security boundary

Keycloak issues bearer JWTs. Services use `sub` as the ownership identity, enforce roles locally and default to denial. `email` is optional delivery/display metadata. Customer-to-customer order access returns `404`; operations privileges do not widen customer order collections or payment submission. The frontend's role checks control presentation only.

| Endpoint Group | Exposure | Authentication | Authorization |
| --- | --- | --- | --- |
| `GET /api/catalog/**` | External | Anonymous for current catalog reads | Public read |
| `POST /api/orders` | External | Required | Customer scope; principal becomes order owner |
| `POST /api/orders/{orderId}/payment` | External | Required | Order owner only; submits the payment for a held customer-hold order (ADR-016), 404 for anyone else |
| `GET /api/orders` | External | Required | Customer scope; always scoped to the caller's own orders, with no operations widening |
| `GET /api/orders/seat-availability` | External | Required | Customer scope; no owned resource, so no ownership check : it is a checkout step, not public browsing |
| `GET /api/orders/{orderId}` | External | Required | Order owner or privileged operations scope |
| `POST /api/orders/{orderId}/refund` | External | Required | Order owner or privileged operations scope, same ownership rule as `cancel` (404, never 403, for anyone else) |
| `GET /api/orders/reconciliation` | Internal operations | Required operations credential | Operations role only; lists the open reconciliation incidents an operator chooses a redrive from |
| `POST /api/orders/{orderId}/reconciliation/redrive` | Internal operations | Required operations credential | Operations role only; redrives an open reconciliation record through the Orders outbox |
| `POST /api/orders/{orderId}/reconciliation/abandon` | Internal operations | Required operations credential | Operations role only; moves a `PAYMENT_PENDING` order past its seat hold into compensation |
| `POST /api/orders/{orderId}/reconciliation/{recordId}/settle` | Internal operations | Required operations credential | Operations role only; settles an abandoned order's unknown payment outcome with provider evidence |
| `POST /api/orders/{orderId}/reconciliation/{recordId}/resolve` | Internal operations | Required operations credential | Operations role only; records evidence-backed resolution of a terminal invariant incident |
| `GET /api/inventory/availability` | Internal | Required service credential | Orders service only; read behind the Orders façade, no propagated user context required |
| `POST /api/inventory/reservations` | Internal | Required service credential plus propagated user context | Orders service only; order owner context required |
| `PUT /api/inventory/reservations/{reservationId}/confirmation` | Internal | Required service credential plus propagated user context | Orders service only; confirms the reservation for its owning order |
| `PUT /api/inventory/reservations/{reservationId}/payment-window` | Internal | Required service credential | Orders service only; opens the one-time T2 payment window for its owning order (ADR-016), idempotent by reservation state, no `Idempotency-Key` |
| `DELETE /api/inventory/reservations/{reservationId}` | Internal | Required service credential plus propagated user context | Orders service only; compensation for owned order |
| `POST /api/inventory/reservations/{reservationId}/refund` | Internal | Required service credential | Orders service only; post-sale seat return for a refund (ADR-014/ADR-015), idempotent by reservation state, no `Idempotency-Key` |
| `POST /api/payments/authorize` | Internal | Required service credential plus propagated user context | Orders service only; payment belongs to owned order |
| `POST /api/payments/refund` | Internal | Required service credential plus propagated user context | Orders service only; idempotently reverses payment for checkout compensation or post-sale refund |
| `GET /api/notifications` | External | Required | Authenticated customer can read only their own notifications |
| `GET /api/notifications/stream` | External | Required | Authenticated customer can receive only their own live notification events |

The configured ingress and the desired gateway authentication requirement are distinct: see [deployment](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/platform/deployment.md). Public Catalog reads are anonymous. Internal service tokens come from the Orders client-credentials registration.

## Shared request headers

| Header | Use |
| --- | --- |
| `Authorization` | Customer/operations JWT on protected APIs; service JWT on internal calls |
| `X-User-Id` | Original customer on internal operations that require propagated ownership context |
| `X-Correlation-Id` | Preserve the caller/event correlation identity across synchronous and asynchronous work; generate one when absent |
| `Idempotency-Key` | Checkout, payment submission, commercial refund, reconciliation mutations, reservation creation and payment operations |

Cancellation and naturally idempotent Inventory confirmation/payment-window/seat-return commands do not require an idempotency key. A key is a retry identifier, never an authentication credential. [Idempotency rules](reservation-confirmation.md#idempotency) define scope, replay, retention and downstream namespaces.

## Catalog

| Method and path | Contract |
| --- | --- |
| `GET /api/catalog/routes` | List routes and schedules |
| `GET /api/catalog/routes/{routeId}` | Route with zone-less `departureTime` and IANA `departureZone` |
| `GET /api/catalog/offers` | List offers |
| `GET /api/catalog/offers/{offerId}` | Offer with per-seat price, currency, machine-readable `refundPolicy`, `termsVersion`, departure time and zone |

`conditions` is display text generated from the refund policy; callers must not parse it. Orders freezes the resolved commercial terms and departure instant at checkout. Catalog currently uses a seeded in-memory repository.

## Orders

### Checkout and payment

`POST /api/orders` accepts `routeId`, non-empty `seats`, `travelDate`, `totalAmount`, required `offerId`, and optional `paymentMethodToken`. It requires a customer JWT and returns `202` with `orderId` and `status: ACCEPTED`. Omitting the token selects customer hold; including it selects direct checkout. See [flow semantics](reservation-confirmation.md#checkout-flows).

Orders resolves the offer synchronously. `totalAmount` must equal its per-seat price multiplied by the seat count, compared by monetary value. Authorization uses the resolved price and currency. Refundability, cutoff, terms version and the composed departure instant are persisted with the order. The nullable database offer column supports legacy orders; it does not make the current request optional.

| Condition | Response |
| --- | --- |
| Empty seats | `400 SEATS_REQUIRED` |
| Past travel date | `400 TRAVEL_DATE_IN_THE_PAST` |
| Missing offer | `400 OFFER_NOT_FOUND` |
| Offer on another route | `400 OFFER_ROUTE_MISMATCH`, with its `routeId` |
| Price disagreement | `400 OFFER_PRICE_MISMATCH`, with per-seat `price` and `expectedTotal` |
| Catalog unavailable | `503 OFFER_UNRESOLVABLE`; no unverified price is accepted |
| Another active customer hold | `409 ACTIVE_HOLD_EXISTS`, with `details.orderId` when still available |
| Same key, different logical request | `409 IDEMPOTENCY_CONFLICT` |

`POST /api/orders/{orderId}/payment` accepts `paymentMethodToken` and a new idempotency key. Only the owner may pay. A valid request atomically stores payment submission and its retry result, enters `PAYMENT_PENDING` and writes the processing event; the response is `202`. Blank tokens return `400 PAYMENT_TOKEN_REQUIRED`. Refusals are `409 HOLD_EXPIRED`, `PAYMENT_ALREADY_STARTED`, `ORDER_NOT_PAYABLE`, or `IDEMPOTENCY_CONFLICT`. A same-key/same-token replay returns its original response. A retry after an unknown outcome must reuse both key and token.

### Reads

| Endpoint | Behavior |
| --- | --- |
| `GET /api/orders` | Own orders, newest first; optional `status`, `limit` default 20/max 100, opaque keyset `cursor`; `nextCursor=null` ends the list. Malformed cursors return `400`. Operations does not widen the collection. |
| `GET /api/orders/{orderId}` | Current status, travel date, amount/currency, offer and frozen refund terms, reservation deadline, and `serverNow`; owner or operations |
| `GET /api/orders/seat-availability?routeId=&travelDate=` | Advisory, privacy-safe seat flags; no reservation/order/principal identifiers |

Order status and `reservationExpiresAt` semantics are defined in the [state table](reservation-confirmation.md#state-models). Clients correct countdowns using `serverNow`; only the server decides eligibility. `termsVersion` is retained for audit, not exposed as a history lookup key. Legacy orders may have null offer/refund fields.

The seat map uses a separate Inventory read client, circuit breaker and bulkhead, with no retry and a default five-second timeout including service authentication (`ORDERS_INVENTORY_READ_TIMEOUT_MS`). A sold-out run returns `200` with unavailable seats. Unknown/unprovisioned route/date returns `404`; Inventory does not distinguish those cases. Past dates return `400 TRAVEL_DATE_IN_THE_PAST`. A same-day run that has departed returns `409 RUN_ALREADY_DEPARTED`; only same-day reads need Catalog departure resolution. Missing dependency responses, including that Catalog check, return `503 SEAT_MAP_UNAVAILABLE` rather than a false sold-out result. A route absent from Catalog still delegates seat existence to Inventory.

Failed customer orders expose only `SEATS_UNAVAILABLE`, `PAYMENT_DECLINED` or generic `CHECKOUT_FAILED`, with a displayable `failureMessage`. Internal/provider failure details are not customer messages.

### Cancel and commercial refund

| Endpoint | Accepted operation | Refusals |
| --- | --- | --- |
| `POST /api/orders/{orderId}/cancel` | Owner or operations cancels before payment; no idempotency key | `409 ORDER_NOT_CANCELLABLE` after payment begins |
| `POST /api/orders/{orderId}/refund` | Owner or operations, idempotency key; asynchronous `202` in `REFUNDING` | `409 ORDER_NOT_CONFIRMED`, `ORDER_HAS_NO_OFFER`, `OFFER_NOT_REFUNDABLE`, `REFUND_CUTOFF_PASSED` |

Refund eligibility uses frozen terms and the Orders database clock. A request accepted before cutoff replays after cutoff; a new request does not. [Refund/recovery semantics](reservation-confirmation.md#post-sale-refunds) define money-first reversal, seat return and unresolved outcomes.

### Reconciliation

All mutations below require operations credentials and an idempotency key. Service credentials are refused. Responses acknowledge asynchronous work with `202`, except `resolve`, which returns `200` and only records incident resolution. They never permit arbitrary business-row edits.

| Endpoint | Inputs and preconditions |
| --- | --- |
| `GET /api/orders/reconciliation?limit=100` | Open incidents; limit clamped to 1 to 500; live holds ordered by nearest deadline, then no-deadline incidents oldest first, then expired holds |
| `POST /api/orders/{orderId}/reconciliation/redrive` | Open incident; resume the order's current process through its outbox |
| `POST /api/orders/{orderId}/reconciliation/abandon` | Expired `PAYMENT_PENDING` order with an open incident; begin compensation without authorizing |
| `POST /api/orders/{orderId}/reconciliation/{recordId}/settle` | Open `PAYMENT_OUTCOME_UNKNOWN`, order `COMPENSATING`, provider `evidenceReference` attesting no remaining money |
| `POST /api/orders/{orderId}/reconciliation/{recordId}/resolve` | Investigated `INVARIANT_VIOLATION`, settled terminal order, evidence reference |

The list contains incident ID, order ID, kind, recorded order state, current order status, failure class, attempt count, creation/update times and reservation deadline. It excludes free-text failure details. Reads require no key and produce no mutation audit line.

| Refusal | Meaning |
| --- | --- |
| `409 NO_OPEN_RECONCILIATION_RECORD` | No incident to redrive or abandon |
| `409 ORDER_ALREADY_TERMINAL` | Redrive refused; stale incidents close. `REFUNDING` remains resumable. |
| `409 RESERVATION_HOLD_EXPIRED` | Payment-phase redrive unsafe; see the unopened-window exception in the protocol |
| `409 RESERVATION_HOLD_NOT_EXPIRED` / `ORDER_NOT_ABANDONABLE` | Abandonment preconditions fail |
| `400 EVIDENCE_REFERENCE_REQUIRED` | Settle/resolve lacks evidence |
| `404 RECONCILIATION_RECORD_NOT_FOUND` | Missing record or record belonging to another order |
| `409 RECONCILIATION_KIND_NOT_SETTLEABLE` / `ORDER_NOT_SETTLEABLE` | Settlement preconditions fail |
| `409 ORDER_NOT_TERMINAL` / `RECONCILIATION_KIND_NOT_RESOLVABLE` | Resolution preconditions fail |
| `409 RECONCILIATION_ALREADY_RESOLVED` | Incident already closed |

Follow the [recovery runbook](../operations/orders-recovery-runbook.md) for action selection and commands.

## Inventory

| Endpoint | Contract |
| --- | --- |
| `GET /api/inventory/availability` | Route/date seat flags (`isAvailable`); service JWT, no propagated customer required |
| `POST /api/inventory/reservations` | Create a time-limited hold; service JWT, user context, idempotency key; returns reservation identity, status and offset-bearing expiry |
| `PUT /api/inventory/reservations/{reservationId}/confirmation` | Owning `orderId`; naturally idempotent; first success and replay return `200 CONFIRMED` |
| `PUT /api/inventory/reservations/{reservationId}/payment-window` | Owning `orderId`; naturally idempotent; returns `200 HELD` with the one-time payment deadline |
| `DELETE /api/inventory/reservations/{reservationId}` | Idempotent hold release; never returns sold seats |
| `POST /api/inventory/reservations/{reservationId}/refund` | Owning `orderId`; naturally idempotent post-sale seat return |

Confirmation refusals include `RESERVATION_NOT_FOUND`, `RESERVATION_ORDER_CONFLICT`, `RESERVATION_EXPIRED`, `RESERVATION_CANCELLED`, and `RESERVATION_SEAT_CONFLICT`. Payment-window refusal also distinguishes `RESERVATION_NOT_HELD`; seat return requires a confirmed reservation and can return `RESERVATION_NOT_CONFIRMED`. Exact request/response types are in [Inventory contracts](../../money-path-contracts/src/main/kotlin/it/polito/cpo/contracts/inventory/ReservationContracts.kt). Atomic transitions and expiry/replay rules belong to the [lifecycle specification](reservation-confirmation.md).

## Payments

`POST /api/payments/authorize` authorizes an order amount/currency using a provider token and an idempotency key. `POST /api/payments/refund` reverses a payment for checkout compensation or commercial refund; an optional positive amount selects a partial reversal. Both are internal service operations. [Payment wire types](../../money-path-contracts/src/main/kotlin/it/polito/cpo/contracts/payments/PaymentContracts.kt) and the [controller](../../payments/src/main/kotlin/it/polito/cpo/payments/controllers/PaymentController.kt) define the executable shapes and HTTP mapping.

The adapter calls the Stripe sandbox charge/refund API. It never stores raw card data. A provider effect can occur before the local authorization is recorded: see the [known payment gap](../operations/orders-recovery-runbook.md#known-gaps). A missing local authorization is not evidence that no charge occurred.

## Notifications

`GET /api/notifications` returns the authenticated customer's process-local notification history. `GET /api/notifications/stream` provides authenticated SSE; bearer tokens travel in headers, never query strings. The current store and duplicate guard are in memory, so history does not survive restart and is not shared across replicas. Durable history remains an open product/API issue ([#131](https://github.com/g05-eurotransit/EuroTransit-application/issues/131)); this documents the implementation limitation rather than declaring that requirement resolved.

## Kafka events

Events carry `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `orderId`, `principalId`, and a typed payload. Consumers must tolerate at-least-once delivery. Tokens, provider secrets and raw payment data never belong in events. The [shared event types](../../money-path-contracts/src/main/kotlin/it/polito/cpo/contracts/events) are the field reference.

| Type/topic | Producer | Current use |
| --- | --- | --- |
| `order-placed` | Orders outbox | Orders checkout consumer |
| `refund-requested` | Orders outbox | Orders commercial-refund consumer |
| `order-confirmed` | Orders outbox | Committed sale fact; not an Inventory command |
| `notification-requested` | Orders outbox | Notifications consumer; optional Catalog trip enrichment |
| `inventory-reserved`, `inventory-failed` | Inventory outbox | Published facts; do not drive Orders stages |
| `payment-authorized`, `payment-declined`, `payment-refunded`, `payment-refund-failed` | Payments outbox | Published facts; do not drive Orders stages |
| `order-placed-dlt`, `refund-requested-dlt` | Kafka error handler | Orders incident recording after exhausted delivery |

## Error model

Errors use `code`, `message`, `correlationId`, and optional structured `details`. Expected classes are `400` invalid input, `401` unauthenticated, `403` disallowed role, `404` absent or hidden resource, `409` state/idempotency conflict, `422` business refusal, and `503` unavailable dependency. Actual payment status mapping is defined by its controller; callers must distinguish business refusal from unknown outcome.

[Observability conventions](../development/instrumentation.md) own logging, metric and trace propagation. [Architecture decisions](architecture-decisions.md) explain the design choices.
