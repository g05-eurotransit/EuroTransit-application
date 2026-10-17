# Reservation Confirmation and Hybrid Checkout Coordination

**Status:** Accepted; implementation is required before hold expiration is enabled

**Scope:** Orders, Inventory, Payments, and the Kafka-driven checkout pipeline

**Related decisions:** ADR-001, ADR-002, ADR-004, ADR-008, ADR-011, ADR-013, and ADR-016

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

### Two checkout flows (ADR-016)

The frontend uses the **customer-hold flow**: `POST /api/orders` without a
payment token holds the seats and parks the order in `HELD_AWAITING_PAYMENT`
for T1 (10 minutes); `POST /api/orders/{orderId}/payment` then submits the
token, Inventory restarts the hold once for T2 (10 minutes), and the pipeline
continues exactly as below. A `POST /api/orders` that carries the token runs the
**direct flow** straight through, as it always has; the load test and canary
jobs depend on it. The order row records which flow it follows
(`payment_deferred`), so a redelivery resumes the right one.

```text
Client                Orders                         Inventory
  | POST /api/orders (no token) -> 202
  |                     | order-placed -> hold ------->  HELD, expires_at = now + T1
  |                     | HELD_AWAITING_PAYMENT (parked)
  | GET /api/orders/{id}: countdown to T1
  | POST /api/orders/{id}/payment -> 202
  |                     | PAYMENT_PENDING + order-placed
  |                     | payment-window ------------->  expires_at = now + T2 (once)
  |                     | authorize -> confirm -> CONFIRMED, as in the direct flow
```

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
9. A reservation's deadline is extended at most once, only by the payment-window
   command, and only while its current deadline has not passed by the Inventory
   database clock. No replay, poll, or customer activity renews it.
10. Payments is called for a customer-hold order only after Inventory has opened
    its payment window and Orders has durably recorded the new deadline.
11. A customer has at most one customer-hold order in `ACCEPTED`, `RESERVING`, or
    `HELD_AWAITING_PAYMENT`, enforced by a unique index in the Orders database.

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

The payment window is not a state. A `HELD` reservation whose window has opened
records `payment_window_started_at` and carries the T2 deadline in `expires_at`;
confirmation, release, and expiration treat it like any other `HELD`
reservation, against whatever `expires_at` now says.

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
PAYMENT_PENDING      -> COMPENSATING            (operator abandonment only, hold expired)

Customer-hold flow only (ADR-016):
RESERVING              -> HELD_AWAITING_PAYMENT  (hold taken, parked for the customer)
HELD_AWAITING_PAYMENT  -> PAYMENT_PENDING        (payment submitted while T1 runs)
HELD_AWAITING_PAYMENT  -> CANCELLED              (customer cancel, or the T1 expiry job)
```

The state meanings and resume points are fixed:

| State | Durable meaning | Redelivery action |
| --- | --- | --- |
| `ACCEPTED` / `RESERVING` | No payment authorization has started. | Repeat the idempotent hold command. |
| `HELD_AWAITING_PAYMENT` | Customer-hold flow: the reservation ID and the T1 deadline are stored, and no payment has been submitted. | None: acknowledge without remote calls. Only the payment command, a cancellation, or the T1 expiry job moves the order. |
| `PAYMENT_PENDING` | The reservation ID is stored and payment authorization has started. In the customer-hold flow, the payment has been submitted and the payment window may not be open yet. | Customer-hold flow, window not yet recorded: repeat the payment-window command, then authorize. Otherwise repeat the idempotent authorization command. |
| `CONFIRMING_INVENTORY` | Payment is authorized and Inventory confirmation is required or its result is unknown. | Repeat the idempotent confirmation command. |
| `COMPENSATING` | Checkout cannot complete after authorization and payment reversal is required or unknown, or an operator abandoned an expired `PAYMENT_PENDING` order whose payment outcome is unknown. | Repeat the idempotent refund command, then release any remaining hold. For an abandoned order, Payments holding no `AUTHORIZED` row opens `PAYMENT_OUTCOME_UNKNOWN` and waits for an operator's settlement. |
| `CONFIRMED`, `FAILED`, `CANCELLED` | Checkout is terminal. | Acknowledge the duplicate event without remote calls. |

Orders transitions to `PAYMENT_PENDING` with a compare-and-set update that also
stores `reservationId`. Customer cancellation is accepted only in `ACCEPTED`,
`RESERVING`, or `HELD_AWAITING_PAYMENT`. From `PAYMENT_PENDING` onward, customer
cancellation returns `409 ORDER_NOT_CANCELLABLE`. This closes the cancellation
race before payment authorization begins. Orders reaches `FAILED` after
authorization only when the payment reversal has completed successfully;
unresolved money remains in `COMPENSATING`.

In the customer-hold flow the stored state stops at `HELD_AWAITING_PAYMENT`
(the reservation ID and deadline are written in the same statement), and three
writers race from it: the payment command, a customer cancellation, and the T1
expiry job. Each is a compare-and-set on that predecessor, the payment command
additionally requiring `reservation_expires_at > CURRENT_TIMESTAMP` and the job
requiring the opposite, so exactly one wins. A cancellation or expiry releases
the hold best-effort right away; if that release fails, the Inventory expiration
sweep frees the seat at the T1 deadline at the latest.

Once the payment window opens, Orders stores the T2 deadline in
`reservation_expires_at` and sets `payment_window_opened` in one compare-and-set,
before it calls Payments. The marker decides what a redelivery does. Without it,
Payments has never been called, so a definitive payment-window refusal can fail
the order with no reversal. With it, a redelivery goes straight to authorization
and never asks for the window again: once an authorization may have been
attempted, a later refusal must not be read as "no money moved" (invariant 8).

`PAYMENT_PENDING` enters `COMPENSATING` only through an operator: a delivery that
exhausted its retries leaves the order there, and once its seat hold has expired
the redrive refuses it, because resuming means authorizing against a hold that no
longer exists. `POST /api/orders/{orderId}/reconciliation/abandon` (below) is that
order's only exit (#140). Nothing on that path authorizes.

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
  "status": "CONFIRMED",
  "expiresAt": "<original-hold-deadline>"
}
```

The body is the shared `ReservationResponse`, so it carries `expiresAt` and not
`orderId`; the caller already knows the order it asked about. Orders treats a `200`
whose `status` is anything other than `CONFIRMED` as an unknown outcome rather than
a success, because that would mean the two services disagree about what the command
does.

Inventory never exposes `ALREADY_CONFIRMED` as a separate reservation state, and the
wire result stays `CONFIRMED` for both the first success and every replay. The replay
count is therefore only observable inside Inventory: `inventory.holds.confirmed` is
incremented solely on a first confirmation, so replays are the difference between
confirmation attempts and that counter. Orders cannot distinguish them and does not
try; its own proxy for redelivery volume is `orders.checkout.resumed`.

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
| Any other `4xx`, or a `4xx` with no decodable code | No definitive business result | Unrecognised | Keep `CONFIRMING_INVENTORY` and redrive. |

Orders resolves an unknown result by repeating the same `PUT`; no separate
reservation-status endpoint is required for checkout recovery.

The last row matters as much as the others. A `401` from a broken service credential
or a `400` from a contract drift means Inventory never evaluated the reservation, so
treating it as a refusal would reverse a paid checkout in order to hide a deployment
fault. Orders acts only on the codes listed above and redrives everything else.

## Payment Window API (ADR-016)

For a customer-hold order, Orders calls this before authorizing:

```http
PUT /api/inventory/reservations/{reservationId}/payment-window
Authorization: Bearer <service-token>
X-Correlation-Id: <checkout-correlation-id>
Content-Type: application/json

{
  "orderId": "<order-id>"
}
```

Like confirmation, it takes no `Idempotency-Key`: the reservation identity, the
stored `orderId`, and `payment_window_started_at` make it naturally idempotent.
It runs in one Inventory transaction:

1. Lock the reservation row with `SELECT ... FOR UPDATE`.
2. Verify that its stored `orderId` matches the request.
3. If it is `EXPIRED` or `CANCELLED`, return the corresponding `409`.
4. If it is `HELD` but `expires_at <= CURRENT_TIMESTAMP`, transition it to
   `EXPIRED`, release only its `HELD` seats, commit, and return
   `409 RESERVATION_EXPIRED`.
5. If `payment_window_started_at` is already set, return the stored deadline
   without writes.
6. Otherwise set `expires_at = CURRENT_TIMESTAMP + T2` and
   `payment_window_started_at = CURRENT_TIMESTAMP`, commit, and return the new
   deadline.

Steps 4 and 6 are the "T1 still valid" check and the extension in one locked
transaction, which is what makes the extension atomic with respect to
expiration, release, and confirmation. T2 is an Inventory property with a
default of 10 minutes; T1 stays the 10-minute hold.

| Condition | HTTP result | Code | Orders action |
| --- | --- | --- | --- |
| `HELD`, same order, before `expiresAt`, window not yet open | `200` | `HELD` with the T2 deadline | Record the deadline and the opened marker, then authorize. |
| `HELD`, same order, before `expiresAt`, window already open | `200` | `HELD` with the stored deadline | Same as above. |
| `HELD` but `expiresAt` has passed, or already `EXPIRED` | `409` | `RESERVATION_EXPIRED` | Fail the unpaid order; Payments is never called. |
| Already `CANCELLED` | `409` | `RESERVATION_CANCELLED` | Fail the unpaid order; Payments is never called. |
| Reservation belongs to another order | `409` | `RESERVATION_ORDER_CONFLICT` | Record an `INVARIANT_VIOLATION`, fail the unpaid order, do not release. |
| Reservation does not exist | `404` | `RESERVATION_NOT_FOUND` | Record an `INVARIANT_VIOLATION`, fail the unpaid order. |
| `CONFIRMED` or `REFUNDED` | `409` | `RESERVATION_NOT_HELD` | Unknown: an unpaid order cannot own a sold reservation. Keep `PAYMENT_PENDING` and redrive to an operator. |
| Timeout, connection failure, circuit open, `5xx`, any other `4xx` | No definitive business result | Transport or unrecognised | Keep `PAYMENT_PENDING` and redrive. |

A `200` whose `status` is anything other than `HELD` is an unknown outcome, as
for confirmation.

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

- the scheduler is off unless `inventory.reservations.expiration.enabled` is `true`,
  which is how the implementation gate below is enforced at runtime; the idempotency
  purge is switched separately through `inventory.idempotency.purge.enabled` and stays
  on, because it does not affect seat availability;
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
- same key with a different fingerprint: return `409 IDEMPOTENCY_CONFLICT`;
- idempotency record already purged, but the permanent unique `order_id` still holds:
  return `409 ORDER_ALREADY_RESERVED`.

The replay never extends `expiresAt` and never creates a replacement hold; the
payment-window command is the only extension and happens once. A customer who
wants another attempt creates a new checkout and therefore a new order ID and
idempotency key.

`ORDER_ALREADY_RESERVED` is reachable only once the 30-minute idempotency retention
has outlived the 10-minute hold deadline, so the surviving reservation is necessarily
terminal by then. Orders therefore treats it as a definitive rejection before any
payment: the order fails, with no reversal and no release. The response does not
currently carry the reservation id, so Orders cannot recover the existing reservation
even when that would be preferable; adding it to the error `details` map is an open
improvement on the Inventory side.

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

In the customer-hold flow, steps 1 to 3 become:

1. `POST /api/orders` without a token stores `ACCEPTED` with
   `payment_deferred = true`, the public idempotency record, and the
   `order-placed` outbox row in one Orders transaction, then returns `202`. The
   unique index refuses a second active hold for the same customer.
2. The consumer transitions the order to `RESERVING` and creates the hold using
   `inv-{orderId}`.
3. Orders atomically stores `reservationId` and the T1 deadline and transitions
   to `HELD_AWAITING_PAYMENT`. The client shows the countdown.
4. `POST /api/orders/{orderId}/payment` atomically transitions to
   `PAYMENT_PENDING` while T1 runs, stores the token and `payment_submitted_at`,
   and writes a new `order-placed` outbox row; then returns `202`.
5. The consumer opens the payment window, stores the T2 deadline and the opened
   marker, and continues with authorization as in step 4 above.

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
| `PAYMENT_PENDING` exhausted and its hold expired | An operator abandons it: transition to `COMPENSATING`, reverse with `refund-{orderId}`, release the hold, then `FAILED`. If Payments holds no `AUTHORIZED` row, keep `COMPENSATING` under `PAYMENT_OUTCOME_UNKNOWN` until an operator settles it with provider evidence; then `FAILED`. Payments writes its row only after the provider answers, so no row does not prove no charge. |
| Hold release outcome unknown before payment | Repeat release; expiration is the final safety net. |
| Customer-hold order reaches T1 unpaid | The T1 expiry job moves `HELD_AWAITING_PAYMENT` to `CANCELLED` and releases the hold best-effort; no notification is sent. |
| Payment submitted after T1 by the Orders clock | The compare-and-set fails; `409 HOLD_EXPIRED`, no state change. |
| Payment submitted twice with different keys or tokens | The first compare-and-set wins; the other request returns `409 PAYMENT_ALREADY_STARTED`. |
| Crash after the payment commit, before any remote call | The new `order-placed` row resumes `PAYMENT_PENDING` without the browser. |
| Payment-window outcome unknown | Keep `PAYMENT_PENDING`, window not recorded; repeat the same `PUT`. Do not call Payments. |
| Payment window refused (`EXPIRED`, `CANCELLED`) | Transition to `FAILED` with no reversal: Payments has never been called. |
| Window opened in Inventory, Orders did not record it | Redelivery repeats the `PUT` and receives the same deadline. |

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
three redelivery attempts one second apart and then publishes the record to the
dead-letter topic.

Note the topic name: `DeadLetterPublishingRecoverer` in the current Spring Kafka
version derives it by appending `-dlt`, so the live topic is `order-placed-dlt`,
not the `order-placed.DLT` spelling used elsewhere in this document. The DLT
consumer and the Strimzi `KafkaTopic` definition must both use the real name.

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
| `PAYMENT_OUTCOME_UNKNOWN` | An abandoned `PAYMENT_PENDING` order for which Payments holds no `AUTHORIZED` row (#140). | Remains open with Orders in `COMPENSATING` until an operator settles it with provider evidence, or a redrive finds and reverses the authorization; then Orders reaches `FAILED`. |

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
Orders terminal-state transaction automatically closes `DELIVERY_EXHAUSTED`,
successfully compensated `PAYMENT_REVERSAL_FAILURE` and `PAYMENT_OUTCOME_UNKNOWN`
records.

An operator abandons a `PAYMENT_PENDING` order whose seat hold has expired, the
one case the redrive refuses (#140):

```http
POST /api/orders/{orderId}/reconciliation/abandon
Authorization: Bearer <operations-token>
Idempotency-Key: <abandon-key>
```

In one Orders transaction it moves the order to `COMPENSATING` (the expiry
re-checked against the database clock), stores the idempotency result and writes
a new `order-placed` outbox row; the compensation above then reverses an
authorized payment and fails the order, or opens `PAYMENT_OUTCOME_UNKNOWN` when
Payments holds no `AUTHORIZED` row. `202 Accepted`. Refused with
`409 RESERVATION_HOLD_NOT_EXPIRED` while the hold runs (redrive instead),
`409 ORDER_NOT_ABANDONABLE` outside `PAYMENT_PENDING`, and
`409 NO_OPEN_RECONCILIATION_RECORD` without an open incident.

An operator settles a `PAYMENT_OUTCOME_UNKNOWN` once the provider shows no money
held for the order (never charged, or refunded there):

```http
POST /api/orders/{orderId}/reconciliation/{recordId}/settle
Authorization: Bearer <operations-token>
Idempotency-Key: <settle-key>

{"evidenceReference": "<provider reference>"}
```

In one transaction it closes the incident with the evidence and writes a new
`order-placed` outbox row; the compensation, finding the outcome settled, fails the
order. The command never moves the order itself.

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
- orders in `PAYMENT_PENDING`, `CONFIRMING_INVENTORY`, and `COMPENSATING` by age,
  measured from the payment submission (`payment_submitted_at`) so that a
  customer's time on the checkout page is never counted as pipeline age;
- customer-hold orders still in `HELD_AWAITING_PAYMENT` after their T1 deadline,
  which is the T1 expiry job's lag;
- payment-window openings, replays, and refusals;
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
- A customer-hold seat is unavailable to others for up to T1 + T2 (20 minutes)
  and, with one hold per customer during T1, an account can keep at most one
  unpaid seat set waiting at a time. Rate limiting per account is not
  implemented.
- A seat released on cancellation can stay unavailable until the T1 deadline if
  the immediate release fails, because that release is best-effort and the
  expiration sweep is the fallback.
- The Orders clock refuses a payment slightly before or admits it slightly after
  the Inventory clock would; Inventory decides in the payment-window
  transaction, so the error only changes which refusal the customer sees.

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
keeps unpaid seats blocked longer. The T2 payment window (ADR-016) is not a grace
period: it is a new deadline set once, under the row lock, before any money
moves, and confirmation is still refused the moment it passes.

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
and pass against PostgreSQL and Kafka. Runtime enforcement is
`inventory.reservations.expiration.enabled`, which defaults to `false`.

- [x] shared reservation states `HELD`, `CONFIRMED`, `EXPIRED`, and `CANCELLED`;
- [x] Orders states `CONFIRMING_INVENTORY` and `COMPENSATING`;
- [x] the confirmation `PUT` and Orders client;
- [x] unique `reservations.order_id` ownership;
- [x] atomic confirm, expire, and release transitions;
- [x] batched `FOR UPDATE SKIP LOCKED` expiration;
- [x] current-state hold replay semantics after expiration;
- [x] state-based Orders redelivery without restarting completed stages;
- [x] typed reconciliation records plus idempotent operations redrive and resolution
  endpoints;
- [x] payment reversal through `refund-{orderId}`;
- [x] confirmation versus expiration and release concurrency tests;
- [x] retry after a lost successful confirmation response;
- [x] crash after Inventory confirmation and before the Orders commit;
- [x] cancellation racing with an in-flight hold;
- [x] payment authorization followed by expired confirmation and successful reversal;
- [x] failed and unknown reversal outcomes;
- [x] proof that release and expiration never modify `CONFIRMED` or `SOLD`;
- [ ] commit-aware metrics and reconciliation alerts — the metrics are complete and
  commit-aware: `orders.reconciliation.required` on every incident, the
  `orders.reconciliation.open` and `orders.pipeline.inflight` gauges with their oldest-age
  companions, and `orders.events.dlt` on the dead-letter path. They are also scrapable:
  `/actuator/prometheus` is permitted (`EuroTransitSecurity.UNAUTHENTICATED_ACTUATOR_ENDPOINTS`),
  exposed (`orders/src/main/resources/application.yaml`), pinned by a test in
  `OrdersSecurityConfigTests`, and collected by the `ServiceMonitor` the configuration repository
  ships for every service. What remains is outside this repository and outside the Orders slice:
  the `PrometheusRule` objects belong to the configuration repository, where
  `platform/kube-prometheus-stack/prometheus-rules.yaml` still defines only the checkout burn-rate,
  latency, and Kafka-lag alerts and nothing for reconciliation. **This is the only item still
  blocking the gate**, tracked as `EuroTransit-configuration#11`.

This list is the release gate for the reservation-expiration capability; none of
its items are deferred design questions.
