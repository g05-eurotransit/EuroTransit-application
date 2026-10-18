# Checkout lifecycle and recovery

Orders commits accepted work before processing it through Kafka. Inventory owns the seat decision; Payments owns provider effects. Unknown remote outcomes preserve the durable phase until retry or reconciliation establishes a result. This document owns the money-path invariants, deadlines, state transitions and replay policy. [API contracts](api-design.md) own request/response shapes and authorization.

## Checkout flows

![Customer-hold and direct checkout sequence](../diagrams/checkout/checkout.svg)

[Full-size sequence](../diagrams/checkout/checkout.svg) · [Interactive HTML](../diagrams/checkout/checkout.html)

| Flow | Before authorization | Deadline |
| --- | --- | --- |
| Customer hold | Checkout without a payment token commits `ACCEPTED`; processing holds seats and parks in `HELD_AWAITING_PAYMENT`. An owner payment command commits `PAYMENT_PENDING` and another outbox event. | T1 is ten minutes from the hold. Inventory opens one T2 payment window, default ten minutes, only while the existing deadline is valid. Orders records it before calling Payments. |
| Direct checkout | Checkout includes a token; processing holds seats and continues directly into authorization. | Original ten-minute hold; no customer waiting stage or extension |

Both flows then authorize, confirm Inventory, and commit Orders confirmation and final outbox events. A local commit failure after Inventory confirmation resumes the same idempotent confirmation; it never compensates merely because the response or local commit was lost. Notifications consumes separately and cannot determine checkout success.

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

## State models

| Orders state | Resume action |
| --- | --- |
| `ACCEPTED`, `RESERVING` | Repeat the original idempotent hold |
| `HELD_AWAITING_PAYMENT` | Park without remote calls; payment, cancellation or T1 expiry decides the next transition |
| `PAYMENT_PENDING`, customer payment window not recorded | Repeat the window command, persist its deadline/marker, then authorize |
| `PAYMENT_PENDING`, window recorded or direct flow | Repeat authorization under the original payment key |
| `CONFIRMING_INVENTORY` | Repeat confirmation |
| `COMPENSATING` | Reverse money, then release remaining held seats; unresolved money stays visible |
| `CONFIRMED`, `FAILED`, `CANCELLED` | Checkout delivery is complete; duplicate checkout events do no work |
| `REFUNDING` | Only the separate refund consumer resumes money reversal and seat return |
| `REFUNDED` | Commercial refund is complete |

The customer-hold payment command, cancellation and expiry job race through compare-and-set from the same predecessor; only one wins. Payment submission checks the Orders database clock as an early refusal, while Inventory remains the authority for the final T1 check and T2 extension. An accepted payment atomically stores the token, submission instant, idempotent response and processing event. A crash after that transaction needs no browser to resume.

The T1 expiry job runs every 15 seconds. It cancels unpaid waiting orders and attempts release; Inventory's independent sweep frees expired held seats. Closing a browser does not cancel a hold. Once payment begins, customer cancellation is refused. Unresolved authorized money cannot become terminal failure before reversal or evidence-backed settlement.

Reservations follow `HELD -> CONFIRMED | EXPIRED | CANCELLED`; a distinct post-sale operation permits `CONFIRMED -> REFUNDED`. Seats follow `AVAILABLE -> HELD -> SOLD`, or return from `HELD` on release/expiry. Only the dedicated post-sale return may change a sold seat back to available.

## Idempotency

Public keys are scoped by principal, operation and request fingerprint. A key identifies one attempt: retry it with the same logical inputs; changed inputs conflict. Payment-submission retry must reuse the provider token too. Existing direct-flow fingerprints remain compatible; tokenless requests use a distinct marker.

| Operation | Key / identity |
| --- | --- |
| Inventory hold | `inv-{orderId}` |
| Payment authorization | `pay-{orderId}` |
| Hold release | `rel-{orderId}` |
| Checkout reversal | `refund-{orderId}` |
| Commercial refund reversal | `pay-refund-{orderId}` |
| Confirmation, payment window, post-sale seat return | Reservation identity plus owning order and persisted state |

Orders retains checkout/payment-submission keys for 24 hours; Inventory retains reservation keys for 30 minutes. Payments' documented authorization retention target is 24 hours, but its current implementation has no automatic purge; do not infer a cleanup guarantee. Its successful refund cache is distinct from recorded provider declines.

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
  the idempotency
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

## Payment-window atomicity

The window command locks the reservation, verifies its order, and evaluates expiry using the Inventory database clock. An expired `HELD` row is expired and its held seats released in that transaction. A still-valid row with `payment_window_started_at` returns the stored deadline; otherwise the command records the one-time extension. Replays after the deadline are refused, even if a window had opened.

Orders does not call Payments until the window result is durably recorded. An ownership/missing-reservation refusal opens an invariant incident and fails the unpaid order; expiry/cancellation fails it unpaid. `RESERVATION_NOT_HELD`, an unrecognized refusal, or a non-`HELD` success is unknown, because an unpaid order must not own sold seats. Preserve `PAYMENT_PENDING` for retry/reconciliation.

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
| Payment submitted after T1 by the Orders clock | The compare-and-set fails; `409 HOLD_EXPIRED`, no state change. The same code answers once the expiry job has cancelled the order. |
| Payment submitted twice with different keys or tokens | The first compare-and-set wins; the other request returns `409 PAYMENT_ALREADY_STARTED`. |
| Crash after the payment commit, before any remote call | The new `order-placed` row resumes `PAYMENT_PENDING` without the browser. |
| Payment-window outcome unknown | Keep `PAYMENT_PENDING`, window not recorded; repeat the same `PUT`. Do not call Payments. |
| Payment window refused (`EXPIRED`, `CANCELLED`) | Transition to `FAILED` with no reversal: Payments has never been called. |
| Window opened in Inventory, Orders did not record it | Redelivery repeats the `PUT` and receives the same deadline. |

## Retry and reconciliation

Transient Inventory/Payments HTTP operations use their configured Resilience4j clients. Kafka dependency recovery uses six jittered redeliveries with approximately 48 to 67 seconds of total waits; permanent/deployment failures use the short three-redelivery budget, and poison payloads go directly to the DLT. These budgets are implemented in [KafkaConfiguration](../../orders/src/main/kotlin/it/polito/cpo/config/KafkaConfiguration.kt); they are not a measured availability guarantee. A wait blocks following work on that consumer and can delay rebalancing.

Exhausted records go to `order-placed-dlt` or `refund-requested-dlt`. DLT listeners retry incident recording until it succeeds; they do not alter business state or infer a payment outcome. Unparseable/unknown-order records produce diagnostic counters instead of inventing an order incident.

| Incident kind | Resolution |
| --- | --- |
| `DELIVERY_EXHAUSTED` | Redrive the current durable process after its dependency recovers |
| `PAYMENT_REVERSAL_FAILURE` | Retry reversal; keep unresolved money nonterminal |
| `SEAT_RETURN_FAILURE` | Retry the sold-seat return after commercial reversal |
| `PAYMENT_OUTCOME_UNKNOWN` | Obtain provider evidence; settle only after no money remains, or redrive a subsequently recorded authorization |
| `INVARIANT_VIOLATION` | Investigate; a settled terminal order can have its incident resolved with evidence |

Redrive atomically records its result and writes `order-placed` or `refund-requested` according to the current order process. An expired `PAYMENT_PENDING` authorization phase is refused and must be abandoned. Exception: a customer-hold order with no recorded payment window has never called Payments, so redrive can still ask Inventory and safely fail unpaid.

Abandonment never authorizes. It enters compensation, reverses an existing authorization and ends failed. Missing/non-authorized local payment state opens `PAYMENT_OUTCOME_UNKNOWN`: provider effects may exist without a local row. Settlement needs provider evidence and resumes the compensator, which checks Payments again before finishing. Terminal completion automatically closes the auto-resolvable incidents; invariant incidents require investigation. The [operator runbook](../operations/orders-recovery-runbook.md) owns action selection and commands.

## Post-sale refunds

Refund eligibility uses the order's frozen terms and stored departure instant against the Orders database clock. Refund acceptance commits `REFUNDING` and `refund-requested` together. The separate consumer reverses money first, then calls Inventory's dedicated sold-seat return, then commits `REFUNDED` and closes auto-resolvable incidents. It does not reuse the checkout consumer or compensation key.

Both downstream calls can be repeated on every delivery. A failed seat return leaves the customer reimbursed and the seat withheld under `SEAT_RETURN_FAILURE`. A failed reversal leaves `PAYMENT_REVERSAL_FAILURE`. There is no terminal `REFUND_FAILED` state that conceals unresolved work. `REFUNDING` is terminal only for stale checkout deliveries, never for refund redrive or incident handling.

Payments retries definitive declines using a provider key suffixed with its recorded decline count; only successful refunds are cached. A decline cached by an older implementation can still replay, because the migration does not delete those records. Refund eligibility for legacy orders without offer terms is refused rather than reconstructed from today's Catalog.

## Verification and accepted limits

The integration suites cover reservation concurrency, replay, lost confirmation response, crash before Orders commit, payment/cancel/expiry races, T2 replay, reversal outcomes, commercial refund and reconciliation. The [DoD](../capstone-dod.md) records acceptance; [cluster evidence](https://github.com/g05-eurotransit/EuroTransit-configuration/blob/main/docs/reliability/chaos-experiment-reports.md) records actual faults and recovery.

This is not a distributed ACID transaction. Seat confirmation and provider effects can precede the next Orders commit. Safety depends on atomic local transitions, stable identities, outboxes and explicit unknown-outcome handling. Provider attempts recorded only after their response remain a [known gap](../operations/orders-recovery-runbook.md#known-gaps). No short fault run certifies a rolling production SLO.
