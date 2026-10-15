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
- Money-path actions must emit audit-ready logs with correlation ID, principal ID, order ID, and outcome. Logs must not contain bearer tokens, secrets, raw payment data, or card details. Orders' implementation: [observability-conventions.md, Money-Path Audit Log](observability-conventions.md#money-path-audit-log).
- Secrets such as database credentials, signing keys, and payment-provider credentials belong in the configuration repository as SealedSecrets, not in application source.

### Endpoint Security Summary

| Endpoint Group | Exposure | Authentication | Authorization |
| --- | --- | --- | --- |
| `GET /api/catalog/**` | Public or authenticated depending on offer visibility | Optional for public catalog; required for personalized offers | Public read or customer scope |
| `POST /api/orders` | External | Required | Customer scope; principal becomes order owner |
| `GET /api/orders` | External | Required | Customer scope; always scoped to the caller's own orders, with no operations widening |
| `GET /api/orders/seat-availability` | External | Required | Customer scope; no owned resource, so no ownership check — it is a checkout step, not public browsing |
| `GET /api/orders/{orderId}` | External | Required | Order owner or privileged operations scope |
| `POST /api/orders/{orderId}/refund` | External | Required | Order owner or privileged operations scope, same ownership rule as `cancel` (404, never 403, for anyone else) |
| `GET /api/orders/reconciliation` | Internal operations | Required operations credential | Operations role only; lists the open reconciliation incidents an operator chooses a redrive from |
| `POST /api/orders/{orderId}/reconciliation/redrive` | Internal operations | Required operations credential | Operations role only; redrives an open reconciliation record through the Orders outbox |
| `POST /api/orders/{orderId}/reconciliation/abandon` | Internal operations | Required operations credential | Operations role only; moves a `PAYMENT_PENDING` order past its seat hold into compensation (#140) |
| `POST /api/orders/{orderId}/reconciliation/{recordId}/settle` | Internal operations | Required operations credential | Operations role only; settles an abandoned order's unknown payment outcome with provider evidence (#140) |
| `POST /api/orders/{orderId}/reconciliation/{recordId}/resolve` | Internal operations | Required operations credential | Operations role only; records evidence-backed resolution of a terminal invariant incident |
| `GET /api/inventory/availability` | Internal | Required service credential | Orders service only; read behind the Orders façade, no propagated user context required |
| `POST /api/inventory/reservations` | Internal | Required service credential plus propagated user context | Orders service only; order owner context required |
| `PUT /api/inventory/reservations/{reservationId}/confirmation` | Internal | Required service credential plus propagated user context | Orders service only; confirms the reservation for its owning order |
| `DELETE /api/inventory/reservations/{reservationId}` | Internal | Required service credential plus propagated user context | Orders service only; compensation for owned order |
| `POST /api/inventory/reservations/{reservationId}/refund` | Internal | Required service credential | Orders service only; post-sale seat return for a refund (ADR-014/ADR-015), idempotent by reservation state, no `Idempotency-Key` |
| `POST /api/payments/authorize` | Internal | Required service credential plus propagated user context | Orders service only; payment belongs to owned order |
| `POST /api/payments/refund` | Internal | Required service credential plus propagated user context | Orders service only; idempotently reverses payment for a failed checkout |
| `GET /api/notifications` | External | Required | Authenticated customer can read only their own notifications |
| `GET /api/notifications/stream` | External | Required | Authenticated customer can receive only their own live notification events |

## Shared Request Headers

| Header             | Required On                                                                                                                                                                         | Contract                                                                                                                                                |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Authorization`    | Protected external APIs and internal service calls according to the endpoint security summary                                                                                       | Carries a bearer JWT for public APIs or service identity for internal calls.                                                                            |
| `X-User-Id`        | Internal service-to-service calls (e.g. `POST /api/inventory/reservations`)                                                                                                         | Explicitly propagates the original customer identity when the `Authorization` header carries a service account token instead of the user token.         |
| `X-Correlation-Id` | All internal calls, logs, and Kafka events                                                                                                                                          | Public clients may provide it; the gateway generates one if absent; services must propagate it unchanged through one checkout flow.                     |
| `Idempotency-Key`  | `POST /api/orders`, `POST /api/orders/{orderId}/refund`, both `POST /api/orders/{orderId}/reconciliation/**` operations, `POST /api/inventory/reservations`, `POST /api/payments/authorize`, `POST /api/payments/refund` | Identifies one mutating attempt. It is not an authorization credential and must be scoped with caller/user context, operation, and request fingerprint. |

Idempotency keys are generated at the caller boundary for the operation they protect:

- Public clients generate a new high-entropy `Idempotency-Key` before calling `POST /api/orders`.
- Public clients reuse that key only for retries of the same checkout attempt.
- A new checkout attempt uses a new key, even if the request payload is identical.
- Orders consumes the public checkout key and stores it with `principalId`, operation, request fingerprint, and result.
- Orders generates separate downstream keys for internal operations, derived from the immutable order id. The scheme is `inv-{orderId}` for the seat reservation, `pay-{orderId}` for payment authorization, `rel-{orderId}` for hold release, `refund-{orderId}` for a checkout compensation's payment reversal, and `pay-refund-{orderId}` for a post-sale refund's payment reversal (ADR-015) — a distinct namespace, because reusing `refund-{orderId}` would make Payments replay the compensation's stored result for a commercial refund.
- Inventory and Payments store their received keys scoped by operation, service caller/user context where applicable, request fingerprint, and result.
- Reusing the same key with the same logical payload returns the current idempotent business result.
- Reusing the same key with a different logical payload returns `409 Conflict`.

Idempotency retention is service-specific:

| Service   | Operation                | Retention  |
|-----------|--------------------------|------------|
| Orders    | Checkout/order placement | 24 hours   |
| Inventory | Seat reservation         | 30 minutes |
| Payments  | Payment authorization    | 24 hours   |

Inventory seat holds last 10 minutes. The 30-minute idempotency record identifies the original reservation but does not freeze a cached `HELD` response, extend the deadline, or create a replacement hold. A replay resolves the reservation's current state. `reservations.order_id` remains unique after idempotency-record purge, so one order cannot acquire a second reservation.

## Service Boundaries

| Service       | Responsibility                                                                                                        | Interaction Style                                                    | Why this boundary exists                                                                                                                                                                                                           |
|---------------|-----------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Catalog       | Exposes routes, schedules, products, and offers.                                                                      | Synchronous read API.                                                | Catalog is read-heavy and tolerant of stale data, so it can be scaled and deployed independently from checkout.                                                                                                                    |
| Orders        | Owns the customer-facing purchase workflow, order state, idempotent checkout acceptance, and its PostgreSQL database. | Synchronous entry API plus asynchronous pipeline orchestration.      | Orders is the money-path coordinator: it accepts checkout requests quickly and records/reports order progress.                                                                                                                     |
| Inventory     | Owns finite seat availability, time-limited seat holds, and the hold-to-sale transition.                              | Synchronous hold and confirmation decisions plus reservation events. | Inventory is the contended resource. It must create one strongly consistent hold before payment and atomically confirm the sale before Orders becomes final. Its reservation store is separate from Orders' order-state ownership. |
| Payments      | Owns payment authorization state and integrates with a provider sandbox/test API.                                     | Synchronous idempotent authorization plus payment events.            | Payment authorization must not double-charge under retries; an immediate success/failure decision is needed before order confirmation.                                                                                             |
| Notifications | Sends confirmations and customer updates.                                                                             | Fully asynchronous event consumer.                                   | Notification failure must not fail checkout. Kafka buffers the work until the service recovers.                                                                                                                                    |

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
  - Returns details for one route, including `departureZone`, the IANA zone id of the departure
    station. `departureTime` is a zone-less `LocalTime`, so a caller holding a `travelDate` needs the
    zone to compose an instant.
- `GET /api/catalog/offers`
  - Lists currently available pricing and ticket offers. Personalized offers require a customer principal.
- `GET /api/catalog/offers/{offerId}`
  - Returns one offer with its machine-readable `refundPolicy` (`refundable`, plus
    `cutoffMinutesBeforeDeparture`, null when not refundable), its `termsVersion`, and the route's
    `departureTime` and `departureZone` denormalized onto the response.
  - Catalog owns the refund policy; Orders applies it. The `conditions` string is display text
    *rendered from* the policy — a caller must never parse it to make a money decision.
  - The response carries a duration and a zone rather than a precomputed instant: Orders composes
    `travelDate + departureTime` in `departureZone` and compares it against its own database clock,
    for the same reason hold expiry uses the database clock. Catalog never learns about orders.
  - `termsVersion` is the monotonic marker Orders persists with the offer id at checkout, so editing
    an offer later cannot retroactively rewrite what a past customer accepted.

### Orders

- `POST /api/orders`
  - Accepts a checkout request: `routeId`, `seats`, `travelDate`, `totalAmount`,
    `paymentMethodToken`, and an optional `offerId`.
  - `travelDate` is the ISO day being travelled (`"2026-09-01"`). A route plus a seat does not identify
    a bookable thing on its own: Inventory keys seats by `(route_id, travel_date, seat_id)`, so the same
    physical seat is sold separately for each day.
  - `offerId` is **required** and decides the price. Orders resolves it against
    `GET /api/catalog/offers/{offerId}` inside the synchronous entry and rejects the checkout unless
    the offer exists, is sold on `routeId`, and its price matches:
  - **An offer price is per seat.** `totalAmount` must equal `price × seats.length`. This is a
    constraint, not an accident of the current seed: every offer is a fare per passenger. An offer
    ever sold per unit — a sleeper compartment rather than a berth — would make this multiplication
    silently wrong, so it must be raised with Catalog before such an offer exists.
  - `400 SEATS_REQUIRED` when `seats` is empty. A checkout with no seats is not orderable whatever
    the offer says, and with the expected total computed as `price × seats.length` an empty list
    would otherwise make a `totalAmount` of `0` pass the price check.
    - `400 OFFER_NOT_FOUND` — no such offer.
    - `400 OFFER_ROUTE_MISMATCH` — the offer is sold on another route. `details.routeId` carries the
      route it does belong to.
    - `400 OFFER_PRICE_MISMATCH` — the amount disagrees with the offer. `details.price` carries the
      per-seat price and `details.expectedTotal` the line total, so a client can re-render rather
      than guess. The request is refused rather than silently corrected: charging an amount other
      than the one the customer was shown is its own bug. Amounts are compared by value, so `24.9`
      and `24.90` agree.
    - `503 OFFER_UNRESOLVABLE` — Catalog could not be reached, so the price could not be verified.
      **The checkout fails closed**: this is a deliberate availability trade, because accepting the
      client's amount when the check cannot be made reopens issue #93 at the moment nobody is
      watching. A `503` rather than a `400` because the request was fine — the answer is "ask again".
  - What gets authorized is the **resolved** price and currency, not the requested ones. `totalAmount`
    is a consistency check on what the client displayed. The currency comes from the offer too: it
    used to be a `"EUR"` literal on the authorization call while the amount came from the request, so
    the two were never checked against each other — latent while every seeded offer is EUR, and not
    latent on the first CHF offer for one of the cross-border routes.
  - The order also freezes the offer's refund terms — refundability, the cutoff, the accepted
    `termsVersion`, and the departure instant composed from `travelDate` and the station's zone — so
    a later catalogue edit cannot rewrite what the customer accepted and the refund needs no Catalog
    call. See ADR-015.
  - **`offerId` became required once the SPA started sending it (#84), which closes #93.** Two
    consequences worth stating. The idempotency fingerprint now always carries the offer segment, so
    every canonical string has fixed arity — that removed a collision the optional form carried, and
    could only be done in the same change. And Catalog is now on the critical path of **every**
    checkout, not only those carrying an offer: while it is unreachable, no checkout is accepted.
    That is the cost of closing #93 and it is deliberate.
  - The `offer_id` **column** stays nullable. Orders placed before the SPA change legitimately hold
    `NULL` and there is nothing to backfill them with; the refund endpoint refuses them, which is the
    right answer.
  - Requires an authenticated customer identity.
  - Returns `202 Accepted` with an `orderId` and initial status after the request has been accepted for processing.
  - Rejects a `travelDate` in the past with `400 TRAVEL_DATE_IN_THE_PAST`. Orders deliberately checks no
    more than that: which dates actually have seats is Inventory's to answer, and a date without seats
    becomes a definitive hold rejection that fails the order through the normal path.
  - Requires a public-client-generated `Idempotency-Key` so client retries do not create duplicate orders.
    The stored request fingerprint includes `travelDate`, so reusing one key for two different days is an
    `IDEMPOTENCY_CONFLICT` rather than a replay of the wrong day. It includes `offerId` for the same
    reason, and only when the field is present, so keys already in flight when the field shipped still
    replay instead of conflicting.
  - Stores checkout idempotency records for 24 hours.
- `GET /api/orders`
  - Returns the authenticated customer's own order history, newest first.
  - Accepts an optional `status` filter, so a "my tickets" view is `?status=CONFIRMED` while the
    unfiltered list stays reachable and a customer can still find a failed attempt.
  - Paginates by keyset, not offset: `limit` (default 20, capped at 100) plus an opaque `cursor`
    returned as `nextCursor`, null on the last page. Offset paging would shift under the concurrent
    inserts that happen precisely while a customer is reading this list.
  - **Unlike `GET /api/orders/{orderId}`, the operations role does not widen this endpoint.** That
    role exists so support can read one order it was given the id of; dropping the principal filter
    on a collection would return every customer's orders in one call. The filter is applied in the
    SQL predicate rather than in the controller, so it cannot be bypassed by a later edit.
  - A malformed cursor is `400`, not a silent restart from the first page, which would make a paging
    client loop over the same page forever.
- `GET /api/orders/seat-availability?routeId=&travelDate=`
  - Returns every seat for that route and travel date with a single `available` flag.
  - Orders fronts this so Inventory stays internal: the browser reaches the `/api/orders` prefix
    through Traefik, and Orders calls `GET /api/inventory/availability` with a service credential.
    Routing it through Catalog instead would serve an authenticated step of the checkout from a
    public anonymous route, and would leave nowhere to create a hold if issue #72 is ever accepted.
  - `travelDate` is required, not optional: seats are keyed by `(route_id, travel_date, seat_id)`,
    so a route alone does not identify a bookable seat.
  - Rejects a `travelDate` in the past with `400 TRAVEL_DATE_IN_THE_PAST`, the same code `POST
    /api/orders` uses, so a client that handles one handles the other (issue #98). It is a second
    line of defence rather than the fix — which seats exist for a past date is Inventory's own truth,
    tracked in #97. Filtering a *same-day* run that has already departed needs the departure instant
    per route and is the remaining half of #98.
  - Inventory's `HELD` and `SOLD` are deliberately collapsed into one flag, and no reservation id,
    order id, or principal is returned: distinguishing held from sold reports another customer's
    in-flight checkout.
  - **The answer is advisory.** It is a read with no lock, so a seat reported free can still lose the
    hold race; `SEATS_UNAVAILABLE` at checkout remains the authoritative answer and clients must
    still handle it.
  - Browsing traffic is isolated from the money path by a separate `inventory-read` circuit breaker
    and bulkhead, a tighter timeout, and **no retry** — a stale seat map is not worth a retry, and
    retrying read traffic is how a slow dependency becomes an outage.
  - `404` when Inventory reports no seats for the route and date. Inventory returns one code for two
    different cases — an unknown route, and a known route with no seats provisioned for that travel
    date — so the façade has nothing better to say than to re-raise it; tracked in #96. A sold-out
    run is not one of those cases: its seat rows exist, so it is a `200` with every seat unavailable.
  - `409 RUN_ALREADY_DEPARTED` when the requested date is **today** and the run has already left.
    ADR-014 requires this filter at read time rather than at the write, because a refund puts the
    seats of a departed run back to `AVAILABLE` by design (#82) — so Inventory's rows are untouched
    and a train that left at 07:40 still reads as bookable at 14:00. A distinct code rather than
    "every seat unavailable": only a code lets the picker say what happened, since a grid of
    greyed-out seats is indistinguishable from a sold-out train and the two need opposite advice.
    `409` rather than `400` because the date is valid and it is the run's state that is wrong.
    Catalog is consulted **only** for a travel date of today — a future date cannot have departed —
    so the common browsing request keeps exactly one dependency. A route Catalog does not know is not
    an error here: Inventory still owns whether the route and date have seats, and answers as it did
    before this filter existed.
  - `503 SEAT_MAP_UNAVAILABLE` when Inventory does not answer at all — a timeout, an open
    `inventory-read` breaker, a 5xx, a refused connection. Deliberately distinct from the `404` above,
    which is Inventory answering: collapsing the two would let "Inventory is down" be read as "no
    seats are free". `503` rather than `504` because the status set is frozen and `503` is its member
    for a dependency being unavailable or a circuit being open. The likeliest trigger is an Inventory
    rollout, during which the breaker opens for about ten seconds (issue #104). The same code
    answers when **Catalog** does not answer the departure lookup above: not knowing whether a train
    has left is not a reason to say it has not, so the endpoint fails closed rather than dropping the
    filter and selling a seat on a departed run.

- `GET /api/orders/{orderId}`
  - Returns the current order status: accepted, reserving, payment-pending, confirming-inventory, compensating, confirmed, failed, cancelled, refunding, or refunded. The last two are a separate durable process (ADR-015), reachable only from confirmed.
  - Includes `travelDate`, distinct from `createdAt`: the day of travel, not the day of purchase.
  - Includes `currency` beside `totalAmount`, so an amount is never presented without the unit it was
    actually authorized in.
  - Includes `offerId` when the checkout carried one, so a client can show what was bought and decide
    whether to offer a refund: an order without an offer has no refund terms and the refund endpoint
    will refuse it (ADR-015). Null on orders placed before the field existed.
  - With it, the refund terms the order was sold under: `refundable`,
    `refundCutoffMinutesBeforeDeparture` (null when not refundable, and null when refundable right up
    to departure), and `departureAt` — the offset-bearing instant the cutoff counts back from,
    already composed from the travel date and the departure station's zone so no client has to do
    that arithmetic. All three are null together for an order placed without an offer.
    These let a client render the right thing; **eligibility is still decided server-side** against
    the Orders database clock (ADR-015). The accepted `termsVersion` is deliberately not exposed: it
    is an audit marker, and a client holding it might use it as a key into a catalogue that no longer
    has that revision.
  - Once seats are held, includes `reservationExpiresAt`: the Inventory hold deadline, carried as an
    offset-bearing instant because it is produced by the Inventory database clock and consumed by a
    browser in an unknown zone. It is the deadline for the *checkout* to converge — covering crash,
    retry, and Kafka lag — and **not** a payment window: the payment token arrives with the checkout
    request and the pipeline runs server-side with no user interaction. Clients must not present it
    as "you have ten minutes to pay". A customer-driven payment step is issue #72, deferred.
  - On a `failed` order, includes `failureCode` and a ready-to-display `failureMessage`. The code is
    drawn from a deliberately small closed set — `SEATS_UNAVAILABLE`, `PAYMENT_DECLINED`, and a
    generic `CHECKOUT_FAILED` — because only the first two describe something a customer can act on.
    Every other internal classification, including every invariant violation, collapses into the
    generic code, so an operator-facing failure class or a provider code can never reach a customer.
    The mapping is an allowlist: a failure class added later collapses by default rather than leaking.
  - Requires the authenticated customer to own the order, unless an operator/admin role is used.
  - Returns `404 Not Found` for customer-to-customer access to avoid revealing another customer's order.
- `POST /api/orders/{orderId}/cancel`
  - Cancels an order that has not yet started payment authorization, releasing any seat hold.
  - Requires the authenticated customer to own the order, or an operations role.
  - Accepted only while the order is `accepted` or `reserving`. From `payment-pending` onward it
    returns `409 ORDER_NOT_CANCELLABLE`, which is what closes the cancellation race before money
    moves; post-sale cancellation is a separate business process and cannot use hold release.
- `POST /api/orders/{orderId}/refund`
  - The post-sale refund (ADR-015): reverses the payment and returns the seat for a `confirmed`
    order, money first. Requires the authenticated customer to own the order, or an operations role;
    same 404-for-non-owner rule as `cancel`.
  - Requires an `Idempotency-Key`, unlike `cancel`: the replay rule below needs a stored record to
    distinguish a retried acceptance from a new request.
  - Returns `202 Accepted` with the order now `refunding`, not `200`: unlike `cancel`, which commits
    a terminal state synchronously, this commits only the acceptance of a refund whose reversal runs
    asynchronously through its own `refund-requested` trigger and consumer — never the `order-placed`
    pipeline, so a redelivered checkout event can never restart a completed sale.
  - Refuses, each with its own `409` code: `ORDER_NOT_CONFIRMED` (wrong state, including an order
    already refunding or refunded), `ORDER_HAS_NO_OFFER` (no offer, so no refund terms — every order
    predating the offer fields is in this set), `OFFER_NOT_REFUNDABLE` (the offer's terms say no),
    and `REFUND_CUTOFF_PASSED` (past `departureAt` minus the offer's cutoff, evaluated against the
    Orders database clock, not a JVM clock, so two replicas agree at the boundary).
  - **The replay rule is two opposite behaviours on one time condition.** An `Idempotency-Key`
    already accepted before the cutoff returns its original result even when the retry arrives after
    it; a genuinely new request after the cutoff is refused. The stored idempotency record is what
    tells the two apart.
  - The reversal itself: `pay-refund-{orderId}` against Payments (never `refund-{orderId}`, which
    checkout compensation already consumes), then the reservation's refund against Inventory. If the
    seat return fails definitively after the money is already reversed, the order stays `refunding`
    with an open reconciliation incident — there is no `refund_failed` terminal state.
- Orders persists order state in `orders_db`.
- `GET /api/orders/reconciliation?limit=100`
  - Requires an operations credential; a customer or a service credential is refused with `403`. Its
    own rule precedes the order-read rule, which would otherwise admit any customer.
  - Lists the **open** incidents, most urgent first — a live seat hold, nearest deadline first; then no
    hold deadline, oldest first; then past the hold — at most `limit` (default 100, clamped to 1–500): `id`,
    `orderId`, `kind`, `orderState` (the phase the incident was recorded in), `orderStatus` (where the
    order is now, what a redrive resumes from), `failureClass`, `attemptCount`, `createdAt`,
    `updatedAt` and the order's `reservationExpiresAt`. The last is the operator's deadline: past it, a `payment_pending`
    order can no longer be redriven (`409 RESERVATION_HOLD_EXPIRED` below).
  - Read only: no idempotency key, no audit line. The free-text `failureDetail` is not returned.
  - How the list is used: [orders-recovery-runbook.md](../operations/orders-recovery-runbook.md).
- `POST /api/orders/{orderId}/reconciliation/redrive`
  - Requires an operations credential and an idempotency key. A service credential is refused with
    `403`: it drives automated internal calls and must not be able to redrive a customer's checkout.
  - Accepts only orders with an open reconciliation record, otherwise
    `409 NO_OPEN_RECONCILIATION_RECORD`.
  - Atomically stores the redrive result and writes a new outbox row, then returns `202 Accepted`. The
    row's type depends on which durable process the order is currently in: `order-placed` for the
    checkout pipeline, `refund-requested` for a `refunding` order (ADR-015).
  - **What a redrive does and does not retry on a stuck refund.** It resumes the refund pipeline from
    the durable `refunding` row, so it does retry a seat return that failed and a delivery that
    exhausted its retries — and a payment reversal Payments refused definitively after #122 was deployed: the
    reversal is re-sent under the same deterministic `pay-refund-{orderId}` key, but Payments no longer
    stores a declined refund as a settled result and sends the provider a key suffixed with the number
    of recorded declines, so the redrive reaches the provider again. If the provider declines again
    the incident stays open, correctly: `closeAutoResolvable` runs only on the way to `refunded`,
    `closeStaleFor`'s callers all exclude `refunding`, and `resolve` is filtered to invariant
    violations.
  - A **genuinely** terminal order (`confirmed`, `failed`, `cancelled`, `refunded`) returns
    `409 ORDER_ALREADY_TERMINAL` and closes any stale record as it refuses. `refunding` answers `true`
    from the same internal terminal check the checkout consumer uses, but is deliberately **not**
    treated as terminal here: the money or the seat may still be unresolved, and closing its incident
    on redrive would be exactly the failure this endpoint exists to fix.
  - A `payment_pending` order whose seat hold has already expired returns
    `409 RESERVATION_HOLD_EXPIRED` and leaves the incident open; its exit is `abandon` below. That phase resumes by authorizing and
    the hold is gone, so the redrive would buy nothing but a charge and its reversal. Only that phase
    is refused: the others either re-take the hold or drive the order to a resolution it needs. The
    same comparison is deliberately not made at authorization time, where the deadline is an Inventory
    instant seconds away and Inventory is the only authority on whether it has passed (invariant 4).
  - Never modifies Inventory, Payments, or terminal Orders state directly. The stored order status is
    what selects the resume action, so the endpoint holds no knowledge of which stage resumes.
- `POST /api/orders/{orderId}/reconciliation/abandon`
  - Requires an operations credential and an idempotency key; a service credential is refused with `403`.
  - Accepts only a `payment_pending` order whose seat hold has expired and that has an open incident.
    Moves it to `compensating` and writes a new `order-placed` outbox row. The pipeline then reverses
    an `AUTHORIZED` payment under `refund-{orderId}`, releases the hold and fails the order
    (`CHECKOUT_FAILED`). If Payments holds no authorization, or one that is not `AUTHORIZED`, the order
    stays `compensating` under a `PAYMENT_OUTCOME_UNKNOWN` incident: Payments writes its row only
    after the provider answers, so a missing row does not prove no charge. A reversal the provider
    refuses leaves `PAYMENT_REVERSAL_FAILURE`, redrivable as usual. Nothing on this path authorizes.
    `202 Accepted`, the same key replays the same response.
  - Refusals: `409 RESERVATION_HOLD_NOT_EXPIRED` (the hold still runs, by either clock: redrive
    instead), `409 ORDER_NOT_ABANDONABLE` (any other status), `409 NO_OPEN_RECONCILIATION_RECORD`.
- `POST /api/orders/{orderId}/reconciliation/{recordId}/settle`
  - Requires an operations credential, an idempotency key and an `evidenceReference` from the payment
    provider: the operator attests that the provider holds no money for the order (never charged, or
    refunded there). Accepts only an open `PAYMENT_OUTCOME_UNKNOWN` of that order, with the order
    `compensating`.
  - Closes the incident with the evidence and writes a new `order-placed` outbox row; the pipeline then
    fails the order. It never moves the order itself and never calls Payments or Inventory.
    `202 Accepted`, the same key replays the same response.
  - Refusals: `400 EVIDENCE_REFERENCE_REQUIRED`, `404 RECONCILIATION_RECORD_NOT_FOUND` (also another
    order's record), `409 RECONCILIATION_KIND_NOT_SETTLEABLE`, `409 RECONCILIATION_ALREADY_RESOLVED`,
    `409 ORDER_NOT_SETTLEABLE`.
- `POST /api/orders/{orderId}/reconciliation/{recordId}/resolve`
  - Requires an operations credential, an idempotency key, a terminal order, and an evidence reference.
  - Resolves only an investigated invariant-violation record; it cannot waive payment reversal or change order state.
  - Refusals: `400 EVIDENCE_REFERENCE_REQUIRED`, `409 ORDER_NOT_TERMINAL`,
    `404 RECONCILIATION_RECORD_NOT_FOUND` (also when the record belongs to another order, so the API
    never reveals that one exists elsewhere), `409 RECONCILIATION_KIND_NOT_RESOLVABLE` for a kind that
    closes automatically, and `409 RECONCILIATION_ALREADY_RESOLVED`.

### Inventory

- `GET /api/inventory/availability`
  - Returns seats and their privacy-safe boolean availability (`isAvailable`) for a specific route and `travelDate`.
  - Internal API; callers must be trusted services such as Orders (seat map façade).
  - Does not require a propagated user context (`X-User-Id`), as it only returns public seat state.
- `POST /api/inventory/reservations`
  - Attempts to create a 10-minute hold for the requested seats.
  - Returns `expiresAt` as an offset-bearing instant. It is derived from the Inventory database clock,
    which is the only authority for expiry, and it reaches a browser as a countdown; a zone-less
    wall-clock reading would be re-interpreted in the viewer's own zone.
  - Internal API; callers must be trusted services such as Orders.
  - Requires an Orders-generated idempotency key derived from the order id, operation, and attempt.
  - Uses a strong consistency decision: the same seat cannot have two active holds.
  - Differentiates holds by route, travel date, and seat, so the same physical seat can be sold for different days.
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
- `POST /api/inventory/reservations/{reservationId}/refund`
  - Transitions a `CONFIRMED` reservation to `REFUNDED` and its seats back to available (ADR-014),
    for the owning `orderId`. Called by Orders' post-sale refund (ADR-015) only after the payment
    reversal has already succeeded.
  - Idempotent **by reservation state**, not by key: a replay against an already-`REFUNDED`
    reservation returns the same `200` instead of erroring, and the endpoint takes no
    `Idempotency-Key` at all — unlike reserve and release.
  - Refusals: `404 RESERVATION_NOT_FOUND`, `409 RESERVATION_ORDER_CONFLICT` (belongs to another
    order), `409 RESERVATION_NOT_CONFIRMED` (any state but `CONFIRMED` or `REFUNDED`),
    `409 RESERVATION_SEAT_CONFLICT` (seat count mismatch during the refund).

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

| Event                    | Producer  | Consumers                                     | Purpose                                                                                                          |
|--------------------------|-----------|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `order-placed`           | Orders    | Orders pipeline observers, optional analytics | Durable record that checkout was accepted.                                                                       |
| `inventory-reserved`     | Inventory | Orders                                        | Indicates that the requested seats are held for 10 minutes and can proceed to payment. Includes the travel date. |
| `inventory-failed`       | Inventory | Orders                                        | Indicates that the hold failed and the order must not proceed to payment. Includes the travel date.              |
| `payment-authorized`     | Payments  | Orders                                        | Indicates payment authorization succeeded.                                                                       |
| `payment-declined`       | Payments  | Orders                                        | Indicates payment failed and compensation may be needed.                                                         |
| `payment-refunded`       | Payments  | Orders                                        | Indicates a refund succeeded (compensation completed after a prior authorization).                               |
| `payment-refund-failed`  | Payments  | Orders                                        | Indicates a refund attempt failed and may need manual reconciliation.                                            |
| `order-confirmed`        | Orders    | Notifications, observability consumers        | Indicates the order is complete and customer notification can be sent.                                           |
| `notification-requested` | Orders    | Notifications                                 | Optional explicit notification command if separated from `order-confirmed`.                                      |
| `refund-requested`       | Orders    | Orders (its own consumer only)                | Durable trigger for the post-sale refund (ADR-015): `confirmed` was accepted into `refunding`, and the reversal must run. Never consumed by the `order-placed` pipeline. |

### `notification-requested` payload

Beyond `recipientEmail` and `message`, the payload carries `type`
(`ORDER_CONFIRMED`/`ORDER_FAILED`), `failureCode` (the `CustomerFailureCode` name, null on a
confirmation), and the trip as `routeId` + `travelDate`.

- **Every field past `message` is optional.** Orders and Notifications are deployed separately, so
  an event produced by the previous version must keep parsing; a consumer that sees no `type`
  renders the plain `message`, exactly as before.
- **The trip travels as identifiers, not as resolved names.** Orders has no Catalog client and does
  not acquire one for this: a confirmation must not depend on Catalog being reachable. Notifications
  resolves the route against `GET /api/catalog/routes/{routeId}` and caches it, and delivers the
  notification without trip context when that fails.
- **Clients branch on `failureCode`, never on an HTTP status.** Inventory answers `409` for five
  distinct conditions and none of them means the seats were taken, while the refusal that does mean
  it is not a `409` at all.
- **`message` is the only field that speaks about money**, and it is rendered verbatim. Those claims
  belong to the closed `CustomerFailureCode` set, which is deliberately careful about what it can
  truthfully assert — `CHECKOUT_FAILED` stays neutral because it covers both a failure before any
  authorization and a checkout reversed after one. A client that recomposes the sentence would
  reintroduce as a presentation bug the very claim Orders avoided making.

## Error Model

All services use a shared HTTP status mapping:

| Status | Meaning                                                                      |
|--------|------------------------------------------------------------------------------|
| `400`  | Malformed request or required header missing                                 |
| `401`  | Missing or invalid authentication                                            |
| `403`  | Authenticated but not authorized                                             |
| `404`  | Resource missing or intentionally not visible                                |
| `409`  | Idempotency conflict, reservation conflict, or incompatible state transition |
| `422`  | Valid request shape but invalid business action                              |
| `503`  | Dependency unavailable, provider unavailable, or circuit breaker open        |

Error responses use structured JSON:

```json
{
  "code": "IDEMPOTENCY_CONFLICT",
  "message": "Idempotency key was reused with a different request",
  "correlationId": "corr_123"
}
```

Validation errors and specific conflicts may add a machine-readable `details` object, but `code`, `message`, and `correlationId` remain mandatory. For example, `ORDER_ALREADY_RESERVED` returns the existing reservation identity:

```json
{
  "code": "ORDER_ALREADY_RESERVED",
  "message": "A reservation already exists for order ord_123 (status: CONFIRMED)",
  "correlationId": "corr_123",
  "details": {
    "reservationId": "res_456",
    "status": "CONFIRMED"
  }
}
```

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
- Orders must flip readiness to refuse new traffic during shutdown while in-flight coroutine work drains or is cancelled cooperatively. Orders drains: the record in progress finishes and the rest is redelivered and resumed from durable state ([orders-shutdown-and-replay.md](../operations/orders-shutdown-and-replay.md)).
- Inventory and Payments must persist idempotency records so duplicate events or HTTP retries return the same outcome.
- Orders persists checkout idempotency records for 24 hours, Inventory persists reservation idempotency records for 30 minutes, and Payments persists authorization idempotency records for 24 hours.
- Inventory holds must expire after 10 minutes if payment does not complete, returning the seat to available state. Inventory idempotency retention must not renew an expired hold.
- Reservation confirmation, expiration, and release must use atomic state transitions. Unknown confirmation outcomes are retried or reconciled; they are not treated as definitive failures.
- Kafka retry exhaustion publishes to `order-placed.DLT`; a DLT consumer creates a reconciliation record without changing the durable checkout phase or issuing speculative compensation. Redrive uses the transactional outbox and resumes from the stored order state.
- Notification failure is a degraded mode, not a checkout failure.
- The checkout SLOs, alerts, and tracing requirements are defined in [slo-observability.md](../operations/slo-observability.md).
