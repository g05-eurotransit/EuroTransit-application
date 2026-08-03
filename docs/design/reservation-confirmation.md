# Reservation Confirmation and Hybrid Checkout Coordination

**Status:** Accepted; implementation is required before hold expiration is enabled

**Scope:** Orders, Inventory, Payments, and the Kafka-driven checkout pipeline

**Related decisions:** ADR-001, ADR-002, ADR-004, ADR-008, ADR-011, and ADR-013

## Context

Inventory creates ten-minute holds for finite seats. A hold is not a sale: until
confirmation, both the reservation and its seats remain `HELD` and can expire or
be released.

The current checkout authorizes payment and confirms the order in Orders without
finalizing the reservation in Inventory. Enabling expiration in that state would
eventually make paid seats available again and allow overselling.

EuroTransit therefore needs an explicit consistency boundary before Orders can
declare checkout complete:

```text
Reservation: HELD -> CONFIRMED
Seat:        HELD -> SOLD
```

## Decision

EuroTransit uses a hybrid coordination model:

- Kafka durably starts and redrives the checkout pipeline through `order-placed`.
- Orders calls Inventory and Payments synchronously for decisions whose result is
  required before the next money-path transition.
- After payment authorization, Orders confirms the reservation through an
  idempotent HTTP command.
- Inventory atomically confirms the reservation and sells its seats.
- Orders records its final state and final outbox events only after Inventory has
  accepted the sale.
- Kafka distributes final facts and asynchronous work, including
  `order-confirmed` and `notification-requested`.

```text
Client
  |
  | POST /api/orders -> 202 Accepted
  v
Orders DB + outbox -- order-placed --> Kafka --> Orders consumer
                                                |
                                                | HTTP: create hold
                                                v
                                             Inventory
                                                |
                                                | HTTP: authorize payment
                                                v
                                             Payments
                                                |
                                                | HTTP: confirm reservation
                                                v
                                             Inventory
                                                |
                                                | Orders DB transaction
                                                v
                               order CONFIRMED + final outbox events
                                                |
                                                v
                                              Kafka
                                  order-confirmed / notification-requested
```

This model does not create a distributed ACID transaction. Its guarantees come
from atomic Inventory transitions, durable Orders state, idempotent remote
operations, transactional outboxes, bounded retries, and explicit
reconciliation.

## Required Invariants

The implementation must preserve all of these invariants:

1. A seat can belong to at most one active `HELD` reservation.
2. A seat can transition to `SOLD` only for the reservation that currently holds
   it.
3. A reservation can transition from `HELD` to exactly one of `CONFIRMED`,
   `EXPIRED`, or `CANCELLED`.
4. Confirmation succeeds only before `expiresAt`, evaluated with the Inventory
   database clock.
5. `CONFIRMED` reservations and `SOLD` seats are never modified by hold release
   or expiration.
6. Repeating the same payment, confirmation, release, or refund command does not
   repeat its business effect.
7. Orders publishes `order-confirmed` only after Inventory has committed
   `CONFIRMED` and `SOLD`.
8. An unknown remote outcome is never converted directly into a business
   failure or compensation.

## State Models

### Inventory

Persisted reservation states are:

```text
HELD -> CONFIRMED
HELD -> EXPIRED
HELD -> CANCELLED
```

Persisted seat states are:

```text
AVAILABLE -> HELD -> SOLD
AVAILABLE -> HELD -> AVAILABLE  (expiration or release)
```

`CONFIRMED`, `EXPIRED`, and `CANCELLED` are terminal reservation states. A new
checkout creates a new order and reservation; it never resurrects a terminal
reservation.

### Orders

The checkout state machine is:

```text
ACCEPTED
   -> RESERVING
       -> PAYMENT_PENDING
           -> CONFIRMING_INVENTORY
               -> CONFIRMED

RESERVING            -> CANCELLED
ACCEPTED             -> CANCELLED
RESERVING            -> FAILED
PAYMENT_PENDING      -> FAILED
CONFIRMING_INVENTORY -> COMPENSATING -> FAILED
```

The state meanings and resume points are fixed:

| State | Durable meaning | Redelivery action |
| --- | --- | --- |
| `ACCEPTED` / `RESERVING` | No payment authorization has started. | Repeat the idempotent hold command. |
| `PAYMENT_PENDING` | The reservation ID is stored and payment authorization has started. | Repeat the idempotent authorization command. |
| `CONFIRMING_INVENTORY` | Payment is authorized and Inventory confirmation is required or its result is unknown. | Repeat the idempotent confirmation command. |
| `COMPENSATING` | Checkout cannot complete after authorization and payment reversal is required or unknown. | Repeat the idempotent refund command, then release any remaining hold. |
| `CONFIRMED`, `FAILED`, `CANCELLED` | Checkout is terminal. | Acknowledge the duplicate event without remote calls. |

Orders transitions to `PAYMENT_PENDING` with a compare-and-set update that also
stores `reservationId`. Customer cancellation is accepted only in `ACCEPTED` or
`RESERVING`. From `PAYMENT_PENDING` onward, customer cancellation returns
`409 ORDER_NOT_CANCELLABLE`. This closes the cancellation race before payment
authorization begins. Orders reaches `FAILED` after authorization only when the
payment reversal has completed successfully; unresolved money remains in
`COMPENSATING`.

## Reservation Confirmation API

Orders calls:

```http
PUT /api/inventory/reservations/{reservationId}/confirmation
Authorization: Bearer <service-token>
X-Correlation-Id: <checkout-correlation-id>
Content-Type: application/json

{
  "orderId": "<order-id>"
}
```

The command does not require an `Idempotency-Key`. The resource identity and the
stored `orderId` provide natural idempotency.

A first successful call and every valid replay return the same response:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "reservationId": "<reservation-id>",
  "orderId": "<order-id>",
  "status": "CONFIRMED"
}
```

Inventory never exposes `ALREADY_CONFIRMED` as a separate reservation state. It
can record replay metrics internally, while the wire result remains
`CONFIRMED`.

The complete HTTP result mapping is:

| Condition | HTTP result | Code | Orders action |
| --- | --- | --- | --- |
| `HELD`, owned by the same order, before `expiresAt` | `200` | `CONFIRMED` | Complete the local order. |
| Already `CONFIRMED` for the same order | `200` | `CONFIRMED` | Complete the local order. |
| `HELD` but `expiresAt` has passed | `409` | `RESERVATION_EXPIRED` | Enter `COMPENSATING`. |
| Already `EXPIRED` | `409` | `RESERVATION_EXPIRED` | Enter `COMPENSATING`. |
| Already `CANCELLED` | `409` | `RESERVATION_CANCELLED` | Enter `COMPENSATING`. |
| Reservation belongs to another order | `409` | `RESERVATION_ORDER_CONFLICT` | Enter `COMPENSATING` and create a reconciliation record. |
| Held seats do not exactly match the reservation | `409` | `RESERVATION_SEAT_CONFLICT` | Enter `COMPENSATING` and create a reconciliation record. |
| Reservation does not exist | `404` | `RESERVATION_NOT_FOUND` | Enter `COMPENSATING` and create a reconciliation record. |
| Timeout, connection failure, circuit open, or `5xx` | No definitive business result | Transport/dependency error | Keep `CONFIRMING_INVENTORY` and redrive. |

Orders resolves an unknown result by repeating the same `PUT`; no separate
reservation-status endpoint is required for checkout recovery.

## Atomic Inventory Implementation

Confirmation runs in one Inventory database transaction:

1. Lock the reservation row with `SELECT ... FOR UPDATE`.
2. Verify that its stored `orderId` matches the request.
3. If it is already `CONFIRMED`, return `200 CONFIRMED` without writes.
4. If it is `EXPIRED` or `CANCELLED`, return the corresponding `409`.
5. If it is `HELD` but `expires_at <= CURRENT_TIMESTAMP`, transition it to
   `EXPIRED`, release only its `HELD` seats, commit, and return
   `409 RESERVATION_EXPIRED`.
6. Update to `SOLD` only seats whose `reservation_id` matches and whose state is
   `HELD`.
7. Verify that the number of updated seats equals the number of seats owned by
   the reservation. A mismatch rolls back the transaction and returns
   `409 RESERVATION_SEAT_CONFLICT`.
8. Transition the reservation to `CONFIRMED` and commit.

Release uses the same reservation-row lock and operates only on `HELD`.
Releasing `CONFIRMED`, `EXPIRED`, or `CANCELLED` is an idempotent no-op; it never
changes `SOLD` seats.

`reservations.order_id` has a unique constraint. This permanent business key
prevents a delayed replay from creating a second reservation for the same order,
even after the HTTP idempotency record has been purged.

## Expiration and Multiple Inventory Replicas

Expiration is decided with `expiresAt` and the Inventory database clock. JVM
clocks are not authoritative.

Each Inventory replica can run the expiration scheduler. Replicas coordinate in
PostgreSQL, not through leader election or ShedLock:

- the scheduler runs every 30 seconds (configurable via `inventory.reservations.cleanup-interval-ms`);
- each transaction claims at most 100 `HELD` reservations with
  `expires_at <= CURRENT_TIMESTAMP`;
- candidates are selected in `expires_at` order using
  `FOR UPDATE SKIP LOCKED`;
- the same transaction transitions the claimed reservations to `EXPIRED` and
  releases only their matching `HELD` seats;
- metrics are incremented only after the transaction commits.

Confirmation, release, and expiration therefore serialize on the reservation
row. Exactly one terminal transition wins. The accepted availability trade-off
is that an unused expired seat can remain unavailable for up to one scheduler
interval before cleanup commits.

## Hold Replay Semantics

The reservation idempotency record identifies the original reservation but does
not cache `HELD` as an immutable business result. A replay of
`POST /api/inventory/reservations` with the same key and fingerprint resolves the
stored reservation and returns its current state.

- current `HELD` before `expiresAt`: return the original hold;
- current `CONFIRMED`: return `CONFIRMED`;
- current or logically expired reservation: return `409 RESERVATION_EXPIRED`;
- current `CANCELLED`: return `409 RESERVATION_CANCELLED`;
- same key with a different fingerprint: return `409 IDEMPOTENCY_CONFLICT`.

The replay never extends `expiresAt` and never creates a replacement hold. A
customer who wants another attempt creates a new checkout and therefore a new
order ID and idempotency key.

## Successful Saga

The successful sequence is:

1. `POST /api/orders` stores `ACCEPTED`, the public idempotency record, and the
   `order-placed` outbox row in one Orders transaction, then returns `202`.
2. The Orders Kafka consumer transitions the order to `RESERVING` and creates a
   hold using `inv-{orderId}`.
3. Orders atomically stores `reservationId` and transitions to
   `PAYMENT_PENDING`, closing customer cancellation.
4. Payments authorizes using `pay-{orderId}`.
5. Orders transitions to `CONFIRMING_INVENTORY`.
6. Orders calls the Inventory confirmation `PUT`.
7. Inventory atomically commits `CONFIRMED` and `SOLD`.
8. Orders atomically transitions to `CONFIRMED`, clears the payment token, and
   writes `order-confirmed` and `notification-requested` outbox rows.
9. Debezium publishes the final events to Kafka.

## Failure and Recovery Policy

| Failure point | Required behavior |
| --- | --- |
| Hold rejected before payment | Transition Orders to `FAILED`; no payment compensation is needed. |
| Cancellation wins while hold is in flight | The `PAYMENT_PENDING` compare-and-set fails; release the hold idempotently and keep Orders `CANCELLED`. |
| Payment declined | Release the hold, transition Orders to `FAILED`, and publish the failure notification through the outbox. |
| Payment outcome unknown | Keep `PAYMENT_PENDING`; repeat authorization with `pay-{orderId}`. |
| Confirmation returns `EXPIRED` or `CANCELLED` | Transition to `COMPENSATING`; reverse payment using `refund-{orderId}`; then transition to `FAILED`. |
| Confirmation returns an ownership, seat, or missing-reservation conflict | Create a reconciliation record, transition to `COMPENSATING`, reverse payment, and finish as `FAILED` after reversal succeeds. |
| Confirmation outcome unknown | Keep `CONFIRMING_INVENTORY`; repeat the same `PUT`. Do not refund or release. |
| Inventory confirmed but the Orders commit failed | Keep `CONFIRMING_INVENTORY`; redelivery receives `200 CONFIRMED` and retries the local transaction. |
| Payment reversal outcome unknown | Keep `COMPENSATING`; repeat the refund with `refund-{orderId}`. |
| Payment reversal definitively fails | Keep `COMPENSATING`, create or update a `PAYMENT_REVERSAL_FAILURE` reconciliation record, and alert. Do not mark the order `FAILED`. |
| Hold release outcome unknown before payment | Repeat release; expiration is the final safety net. |

`POST /api/payments/refund` is the EuroTransit compensation command for an
authorized payment. It is idempotent by `refund-{orderId}`. The Payments adapter
maps it to a provider void when funds are authorized but not captured, and to a
provider refund when capture has occurred. Orders never chooses between provider
operations.

Normal checkout compensation never reverses a reservation that Inventory has
already confirmed. The checkout APIs reject cancellation after
`PAYMENT_PENDING`; post-sale cancellation is outside this protocol and cannot
use hold release.

## Retry, Dead Letter, and Reconciliation

Within one pipeline delivery, transient Inventory and Payments calls use the
existing Resilience4j policy: one initial call plus two retries with exponential
random backoff. Business `4xx` results are not retried by the HTTP client.

If a transient or unknown outcome remains, the orchestrator rethrows it. The
Kafka listener does not commit the offset. The existing error handler performs
three redelivery attempts one second apart and then publishes the record to
`order-placed.DLT`.

A DLT consumer creates or updates a `DELIVERY_EXHAUSTED` Orders reconciliation
record containing `orderId`, the durable order state, correlation ID, failure
class, attempt count, and timestamps. It also increments
`orders.reconciliation.required` and triggers an alert. It does not change the
durable checkout state and does not issue speculative release or refund
commands.

Reconciliation record kinds are fixed:

| Kind | Created for | Resolution |
| --- | --- | --- |
| `DELIVERY_EXHAUSTED` | A valid event reached `order-placed.DLT`. | Automatically resolved when redrive reaches a terminal Orders state. |
| `INVARIANT_VIOLATION` | Missing reservation, order ownership conflict, or seat-count conflict. | Payment is reversed, Orders reaches `FAILED`, and an operator resolves the record after investigation. |
| `PAYMENT_REVERSAL_FAILURE` | A provider reversal returned a definitive failure or repeatedly remained unknown. | Remains open with Orders in `COMPENSATING` until redrive completes the reversal; then it resolves automatically. |

An operator redrives reconciliation through this internal API:

```http
POST /api/orders/{orderId}/reconciliation/redrive
Authorization: Bearer <operations-token>
Idempotency-Key: <redrive-attempt-key>
X-Correlation-Id: <correlation-id>
```

The caller requires the operations role. In one Orders transaction, the command
validates that an open reconciliation record exists, stores the redrive
idempotency result, and writes a new `order-placed` outbox row. It returns
`202 Accepted`; repeating the same key returns the same response. A terminal
order returns `409 ORDER_ALREADY_TERMINAL` and closes any stale reconciliation
record.

The preserved order state selects the correct idempotent resume action from the
state table above. A repeated DLT updates the existing `DELIVERY_EXHAUSTED`
record's attempt count and latest failure instead of creating a duplicate. The
Orders terminal-state transaction automatically closes `DELIVERY_EXHAUSTED` and
successfully compensated `PAYMENT_REVERSAL_FAILURE` records.

An operator resolves an investigated `INVARIANT_VIOLATION` through:

```http
POST /api/orders/{orderId}/reconciliation/{recordId}/resolve
Authorization: Bearer <operations-token>
Idempotency-Key: <resolution-key>
X-Correlation-Id: <correlation-id>
Content-Type: application/json

{
  "evidenceReference": "<incident-or-audit-reference>"
}
```

The endpoint requires a terminal order, a matching open
`INVARIANT_VIOLATION`, and a non-empty evidence reference. It atomically stores
the resolution and its idempotency result. It cannot waive a pending payment
reversal or change business state. An operator never edits Inventory, Payments,
or Orders database rows directly.

Unparseable Kafka records also go to `order-placed.DLT`; they cannot create a
reconciliation record without a valid order ID and instead trigger a poison-event
alert.

## Kafka Responsibilities

Kafka remains central to EuroTransit:

- `order-placed` durably triggers checkout and allows crash recovery;
- at-least-once delivery redrives non-terminal Orders states;
- `order-confirmed` propagates a fact that has already committed;
- `notification-requested` decouples notification from checkout success;
- Kafka buffers asynchronous consumers during temporary outages;
- the DLT preserves records that exhaust automatic retry.

Kafka is not the confirmation decision channel because consumer lag and retry
are normal while the hold has a hard deadline. The system never assumes that an
event published before `expiresAt` is consumed before `expiresAt`.

`order-confirmed` is a final fact, not a command to Inventory. Orders can publish
it only after Inventory accepts the sale and Orders commits its local final
state.

## Observability Requirements

The implementation records:

- confirmation attempts, successes, business conflicts, transport failures, and
  idempotent replays;
- expiration batches, committed expirations, duration, and errors;
- orders in `PAYMENT_PENDING`, `CONFIRMING_INVENTORY`, and `COMPENSATING` by age;
- payment reversal successes, failures, and unknown outcomes;
- Kafka retries, DLT records, reconciliation records, redrives, and age;
- traces spanning the Orders consumer, Inventory, Payments, both outboxes, and
  final Kafka consumers.

Alerts fire for any reconciliation record, failed payment reversal, reservation
seat conflict, or non-terminal order older than the checkout recovery SLO.

## Accepted Trade-offs and Residual Risks

The team accepts these consequences:

- Inventory is a synchronous dependency for final checkout, so an Inventory
  outage delays completion even though the initial order was accepted with
  `202`.
- Confirmation adds one HTTP round trip and one Inventory transaction to the
  terminal path.
- Inventory can be `CONFIRMED` while Orders temporarily remains
  `CONFIRMING_INVENTORY`; idempotent redelivery converges the states.
- A payment can be authorized and later voided or refunded when confirmation
  loses to expiration.
- Confirmation near `expiresAt` can be conservatively rejected even when the
  seat has not yet been reassigned.
- Reservation-row locking and hot-seat contention limit Inventory throughput;
  consistency takes priority over accepting every checkout.
- DLT and reconciliation are necessary operational safety mechanisms, not proof
  of exactly-once message delivery.
- Orders and Inventory share a versioned confirmation contract and require
  backward-compatible deployment.

These trade-offs are preferable to overselling a finite seat or reporting an
order as confirmed before Inventory has accepted the sale.

## Rejected Alternatives

### Confirm through `order-confirmed`

Rejected because it publishes a final fact before Inventory accepts the sale and
makes correctness depend on Kafka lag relative to the hold deadline. A complete
event choreography would require request/result topics, more intermediate
states, inboxes, timeouts, and additional reconciliation without improving the
Inventory invariant.

### Grace period before expiration

Rejected as a correctness mechanism because it only moves the race boundary and
keeps unpaid seats blocked longer.

### Confirm seats before payment authorization

Rejected because a crash or decline would leave sold but unpaid seats and would
require normal checkout compensation to reverse `SOLD`, weakening the state
model.

### Shared Orders and Inventory database

Rejected because EuroTransit has separate service and datastore ownership. It
would reduce one distributed consistency window but violate the selected service
boundaries, and Payments would remain external.

## Implementation Gate and Acceptance Tests

Hold expiration must not be enabled until all of the following are implemented
and pass against PostgreSQL and Kafka:

- shared reservation states `HELD`, `CONFIRMED`, `EXPIRED`, and `CANCELLED`;
- Orders states `CONFIRMING_INVENTORY` and `COMPENSATING`;
- the confirmation `PUT` and Orders client;
- unique `reservations.order_id` ownership;
- atomic confirm, expire, and release transitions;
- batched `FOR UPDATE SKIP LOCKED` expiration;
- current-state hold replay semantics after expiration;
- state-based Orders redelivery without restarting completed stages;
- typed reconciliation records plus idempotent operations redrive and resolution
  endpoints;
- payment reversal through `refund-{orderId}`;
- confirmation versus expiration and release concurrency tests;
- retry after a lost successful confirmation response;
- crash after Inventory confirmation and before the Orders commit;
- cancellation racing with an in-flight hold;
- payment authorization followed by expired confirmation and successful reversal;
- failed and unknown reversal outcomes;
- proof that release and expiration never modify `CONFIRMED` or `SOLD`;
- commit-aware metrics and reconciliation alerts.

This list is the release gate for the reservation-expiration capability; none of
its items are deferred design questions.
