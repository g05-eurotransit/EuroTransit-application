# Orders Slice: Working Plan

Working plan for the Orders slice owner. It is deliberately incomplete: only the current step and
the next one are written in detail. **When a step is finished, write the following step here before
starting it.** This keeps each step doable in a fresh session without reconstructing context.

Last updated: 2026-09-10.

## Decisions already taken

Do not re-litigate these; they were settled deliberately.

| Decision | Choice |
| --- | --- |
| Plan scope | The Orders slice, plus a sequencing proposal for the rest of the team |
| Optimise for | A complete, demonstrable money path — refund included |
| Post-sale refund | Start on the Orders side now; do not wait on the dependencies |
| #72 explicit checkout hold | Left open for now; the analysis is posted as a comment, decision deferred |

## Findings that change the picture

Read these before planning anything around the refund. They come from the code, not from the issue
text, and two of them contradict the issue text.

**#83 (Payments, a second refund per order) is already satisfied.** `payment_refunds` has no
uniqueness constraint on `authorization_id` (`payments/src/main/resources/db/migration/V1__init.sql`),
so two refunds for one order are representable today. Idempotency is keyed on the primary key of
`idempotency_records` and looked up with `findById(idempotencyKey)`
(`PaymentService.refund`), so `refund-{orderId}` and `pay-refund-{orderId}` cannot alias. The
commercial refund can be built against the live endpoint. The only open question in #83 —
whether `payment-refunded` events distinguish a compensation from a commercial refund — does not
block Orders, because the Orders refund calls Payments synchronously and consumes no payment events.

**#85 (Catalog) is a real gap but a small one.** `Offer.conditions` is display prose and
`Route.departureTime` is a zone-less `LocalTime`, in an in-memory repository holding six offers.
It is an hour of work that nobody has started, not an architectural dependency.

**The largest piece of the refund is inside Orders and is not blocked.** Orders has no concept of an
offer: `CheckoutRequest` carries no `offerId`, the orders table has no offer column, and there is no
Catalog client anywhere in `orders/src/main`. Everything downstream of the refund policy depends on
closing that gap first, and none of it waits on anyone.

**Two of the five Orders canary gates in `EuroTransit-configuration#27` do not measure what they
claim.** `non-terminal-orders` and `open-reconciliation-incidents` filter `orders_pipeline_inflight`
and `orders_reconciliation_open` by `rollouts_pod_template_hash`, but both gauges are produced by a
scheduled job querying the whole database, so every replica publishes the same global value. The
filter attributes nothing, `sum()` multiplies by replica count, and `result == 0` on open incidents
makes the canary unpromotable the moment a chaos experiment leaves a record open — which is what
those experiments are for. Attributable per-pod replacements exist: `orders_events_dlt_total`,
`orders_checkout_failed_total`, `orders_checkout_completed_total{result="confirmed"}`.

Related and worth writing down beside the template: under ADR-011 the pipeline runs in a Kafka
consumer, so the `TraefikService` weight controls only the HTTP entry. Which instance runs the money
path is decided by partition assignment, so `p95` on `http_server_requests` covers a persist and an
insert and none of reserve, pay or confirm. Posted as a review comment on that PR.

**The availability façade is not a contested decision.** The design was stated on #68 and Inventory
implemented it to spec: the path, the `travelDate` parameter, and the collapse of `HELD`/`SOLD` into
a single `isAvailable` flag all match what was asked. The `api-design.md` row naming Catalog as the
caller is inconsistent with the code it documents in the same commit — a doc slip to correct, not a
counter-proposal to negotiate.

## Step list

| Step | What | Status |
| --- | --- | --- |
| 0 | Unblock everyone else: four messages, no code | **done** (2026-09-10) |
| 1 | Seat availability façade on Orders | **done** — merged as `a3ce792` |
| 2 | Refund part 1 — the ADR and `offerId` in the checkout contract | **done** — see the outcome below |
| 3 | Refund part 2 — Catalog client and policy resolution | **next, detailed below** |
| 4 | Refund part 3 — refund state machine and endpoint | to be written |
| 5 | Orders canary with metric-gated promote/abort | to be written; findings already recorded below |
| 6 | Chaos experiments, postmortem, agent log | to be written |

Steps 2 to 4 are one feature split by dependency, not three features. Step 3 needs part of #85 to
have landed — `GET /api/catalog/offers/{offerId}` for 3a, the refund fields for 3b; step 2 needed
neither, which is why the request goes out in step 0.

---

## Step 0 — Unblock everyone else — DONE 2026-09-10

No code. Four messages that let other people work while step 1 is in progress.

**0.1 — PR #88 merged** as `65dcd06`, without review. Two things follow from that and are not yet
resolved:

- the contract change is breaking under ADR-010, and its blast radius is now wider than when the
  branch opened because the refund path also returns `ReservationResponse`. The risk is at rollout,
  not at merge: on `dev` both services come from the same commit, but the configuration repository
  has per-service image build workflows, so deploying Orders or Inventory alone is a reachable path.
  **Warn Carmelo and Matteo before the next rollout.**
- `team-agentic-implementation-plan.md:17` requires a second human reviewer for agent-generated
  changes touching payments or inventory consistency. Nobody on the team practises it — the last
  eight merged PRs all have zero reviews — while the DoD has an item on agent review gates that
  currently has no evidence behind it. A team-level problem to raise, not a blocker for this slice.

**0.2 — Analysis posted on #72**, issue left open. Draft kept in the appendix.

**0.3 — Field spec posted on #85.** Catalog implements a specification rather than inventing one:
a machine-readable refundability rule per offer (refundable yes/no plus a cutoff as a duration
before departure), a zone on the route so `travelDate + departureTime` is an instant, and a version
marker so Orders can persist which terms the customer accepted. One more gap surfaced while writing
it and is included: `GET /api/catalog/offers/{offerId}` does not exist — `CatalogController` exposes
only `/routes`, `/routes/{routeId}` and `/offers`, and `RouteRepository` has no `findOfferById`, so
without it Orders would fetch the whole catalogue per checkout. The comment also lists the two
existing offers whose prose does not map cleanly to a rule (of-002, of-003).

**0.4 — Open points posted on #68.** The `404 ROUTE_NOT_FOUND` that conflates two cases behind one
code, and the missing filter for departed same-day seats that ADR-014 now returns to `AVAILABLE`.

**Corrected 2026-09-10, after reading `SeatService`.** The 404 was described here as conflating
"unknown route" with "sold out". That is wrong, and worth having right so nobody fixes the wrong
thing: the branch throws when the seat query returns **zero rows** (`SeatService:28`), and a
sold-out run has rows — `HELD`/`SOLD` — so it correctly returns `200` with every seat `false`. The
real ambiguity is "no such route" versus "route exists, no seats provisioned for that travel date".
Split into #96; the departed-seat filter into #97 (past dates, Inventory) and #98 (same-day, the
Orders façade, because it needs Catalog's departure time and zone).

Links: [#72](https://github.com/g05-eurotransit/EuroTransit-application/issues/72#issuecomment-5616516176) ·
[#85](https://github.com/g05-eurotransit/EuroTransit-application/issues/85#issuecomment-5616518795) ·
[#68](https://github.com/g05-eurotransit/EuroTransit-application/issues/68#issuecomment-5616520879)

---

## Step 1 — Seat availability façade

`GET /api/orders/seat-availability?routeId=&travelDate=`, delegating to Inventory so Inventory stays
internal.

**Why before the refund**, given the priority is a demonstrable money path: it is roughly a day, it
closes #68, and it takes Orders off the critical path of #84 item 3 so the frontend can work while
the refund is in progress. A seat picker with real data is also one of the most visible things in a
demo. If #85 lands very quickly, reversing the order of steps 1 and 2 is fine.

**Prerequisites.** PR #88 merged — it touches `WebClientInventoryClient`, and stacking two branches
on the same files repeats the merge work. The sweeper (#81, configuration PR #31) must be enabled
before the *frontend* consumes this, per the sequencing already stated on #68: without it, seats held
by abandoned checkouts read as permanently unavailable and look like an availability bug. Building
the façade does not have to wait for it.

**Content, in order:**

1. **Correct the `api-design.md` row** that names Catalog as the caller, and add
   `GET /api/orders/seat-availability` to the endpoint table.

2. **Separate `inventory-read` Resilience4j instances** in `ResilienceConfiguration`. Register a
   circuit breaker and a bulkhead; deliberately **do not register a retry**, and say why in a
   comment: a stale seat map is not worth a retry, and retrying read traffic is how a slow dependency
   becomes an outage. Today a single `"inventory"` instance is shared by hold, confirm and release —
   bulkhead of 20 concurrent calls failing fast, breaker opening at a 50% slow-call rate over 2s in a
   20s window and staying open 10s — so without this a browsing spike opens the breaker the money
   path uses to create holds. The DoD requires bulkheads to isolate checkout from unrelated traffic.

3. **A separate client class**, not another method on `WebClientInventoryClient`. That class binds
   the three money-path operators as fields; a read method added beside them will eventually get the
   wrong chain applied. A separate class makes the mistake impossible rather than discouraged. Use a
   timeout tighter than the money path's 2s.

4. **An Orders-owned response DTO**, not `SeatAvailabilityResponse` from `money-path-contracts`. Same
   reasoning as `CustomerFailureCode` versus `FailureClassifier`: if the public response *is* the
   internal contract, an Inventory change becomes a public API change.

5. **Authenticated customer, no ownership check** — there is no owned resource — with the
   corresponding entry in `OrdersSecurityConfigTests`.

6. **RED metrics tagged separately from checkout**, so a slow seat map cannot burn the checkout SLO
   or block the Orders canary.

7. **Decide how to surface Inventory's 404**, informed by whatever #68 answers about the ambiguity.

**Done when:** the endpoint is merged, `api-design.md` documents it, the resilience instances are
separate and tested, and #84 item 3 is unblocked with the response shape communicated.

---

## Appendix: draft comment for #72

> Recording where this stands from the Orders side, and what it costs to leave it open. Not a
> decision — the proposal is sound and the analysis holds up. This is about budget and sequencing.
>
> **What accepting it means, concretely.** It is not an Orders feature; it is a change across four
> slices. Orders gains four durable states (`HELD_AWAITING_PAYMENT`, `PAYMENT_ACTION_REQUIRED`,
> `PAYMENT_PROCESSING`, `PAYMENT_CANCELLING`), three endpoints, and a durable trigger separate from
> the `order-placed` consumer. Inventory gains an operation that opens a bounded payment window on a
> hold, plus a release identifiable by `orderId` for the case where Orders crashed before storing
> `reservation_id`. Payments has to persist the provider payment identity, accept and
> signature-verify webhooks, deduplicate provider event ids, and publish durable status changes. The
> configuration repository has to expose the webhook route and supply its secret through the sealed
> secret process. The frontend has to persist idempotency keys in session storage, because a reload
> after an unknown response would otherwise create a second order and a second hold.
>
> **What we gain.** A realistic payment flow. Today `paymentMethodToken` arrives in the checkout body
> and the whole pipeline runs server-side with no user interaction, which means there is no 3-D
> Secure and no Strong Customer Authentication anywhere in the money path. For a European rail
> marketplace that is not a realistic flow.
>
> **What the Definition of Done asks for.** Only that Payments integrates with a provider sandbox,
> which #87 has done with Stripe test mode. The DoD does not require this proposal.
>
> **What leaving it open costs.** Every decision downstream has to hedge. The hold deadline shipped
> in #88 cannot be described as what it looks like: the copy constraint says it is the deadline for
> the checkout to converge — covering crash, retry and Kafka lag — and no client may present it as
> "you have ten minutes to pay". The availability façade has to be designed as an advisory read while
> knowing it may become the point where the hold is created. Both constraints stay in force for as
> long as this stays undecided.
>
> **Proposal:** keep it open but explicitly out of scope for the current delivery, and revisit only
> if time remains after the money path is complete and demonstrable. If instead we want to accept it,
> it should be planned as its own cross-slice block with Inventory, Payments and the configuration
> repository involved from the start — not as an Orders task.


---

## Step 1 — outcome

Merged as `a3ce792` (PR #92), CI green. Full build was green at **289 tests, 0 failures** (+14).

- `inventory-read` circuit breaker and bulkhead registered separately from `inventory`, with a
  1s slow-call threshold and a 1s timeout, and **no retry registration**. The absence is asserted
  by `a failing read is attempted once, unlike the money path`, which drives both clients at the
  same failing handler and counts requests: 1 versus 3.
- `InventoryReadClient` / `WebClientInventoryReadClient` kept apart from the money-path client, so
  the wrong resilience chain cannot be applied by accident, plus a `dev` mock with deterministic
  availability.
- `GET /api/orders/seat-availability?routeId=&travelDate=`, Orders-owned response DTO, two fields
  per seat and no ids.
- `api-design.md`: the row naming Catalog as the caller corrected, the façade documented.

Deliberately left for Inventory, both raised on #68 and now split out of it: the ambiguous 404
(#96 — see the correction under step 0.4; it is route-versus-date, not route-versus-sold-out) and the
missing filter for departed seats (#97 for past dates, which Inventory can fix alone, and #98 for the
same-day case, which is the façade's because it needs Catalog's departure time and zone).

Still open before the frontend can consume it: the sweeper (#81 / configuration PR #31) must be
enabled, or seats held by abandoned checkouts read as permanently unavailable.

---

## Step 2 — The refund ADR, and `offerId` in the checkout contract

Two things that are entirely Orders' and blocked by nobody. Deliberately no refund behaviour yet:
this step only makes Orders able to say *which offer was bought*, which nothing downstream can work
without.

### 2a — Write the ADR

The design is already settled and written out in the body of #86; what is missing is the record.
ADR-014 covers Inventory's `SOLD -> AVAILABLE` transition, and there is no counterpart for the
Orders side. Next free number is **ADR-015**.

It should capture the decisions that are non-obvious enough to be re-litigated otherwise:

- `CONFIRMED -> REFUNDING -> REFUNDED`, with `REFUNDING` as the durable resume point and
  deliberately **no** `REFUND_FAILED`. Unresolved money stays in `REFUNDING` with an open
  reconciliation record, exactly as `COMPENSATING` does, rather than reaching a terminal state that
  hides it;
- its own durable trigger and consumer, **not** the `order-placed` pipeline. That consumer must keep
  treating `CONFIRMED` as terminal, or a redelivery would restart a completed checkout;
- reverse the money first, return the seat second. If the seat return fails definitively the
  customer already has their money and a `SEAT_RETURN_FAILURE` reconciliation record stays open;
- a new idempotency key scheme, `pay-refund-{orderId}`. `refund-{orderId}` is already consumed by
  checkout compensation; reusing it would make Payments replay the compensation's stored result and
  return a 200 while no money moves. This is safe today — verified, see the findings above — because
  `payment_refunds` has no uniqueness constraint on the order and idempotency is keyed by key;
- the cutoff is compared against the **Orders database clock**, not a JVM clock, for the same reason
  invariant 4 uses the database clock for hold expiry: replicas disagree at the boundary;
- a replay of an idempotency key accepted *before* the cutoff must return the original result even
  when it arrives after it, while a *new* request after the cutoff is refused. Two opposite
  behaviours on the same time condition, and the idempotency record is what distinguishes them.

### 2b — Persist the offer

Orders has no concept of an offer: no `offerId` on `CheckoutRequest`, no column, no Catalog client.

- add `offerId` to `CheckoutRequest`, **nullable to begin with**. The frontend is a different owner
  and sending it is part of #84; making it required in the same commit would break checkout until
  that lands, and a broken checkout is worse than an unrefundable order. The refund endpoint will
  refuse an order that has no `offerId`, which is the correct answer for anything bought before this
  ships anyway. Tighten to required once the SPA sends it;
- migration `V13`, plus the column on the entity and the write in the checkout transaction;
- surface it on `OrderView` so the SPA can show what was bought and decide whether to offer a refund
  button;
- tell Erestina on #84 that the field exists and is accepted, so the SPA change can be scheduled.

**Found while scoping this step, and why step 3 matters more than the refund alone.** The amount
Orders authorizes is supplied by the client and validated against nothing: `CheckoutRequest`
carries `totalAmount`, `CheckoutOrchestrator` persists it at :161 and sends it to Payments at :327,
so a checkout submitted with `0.01` is authorized for one cent and confirmed as a normal sale.
Nothing downstream catches it — Payments authorizes what it is given, Inventory has no notion of
price. The cause is the same gap this step closes: with no way to resolve what was bought, there is
nothing to compare the amount against. Tracked as #93, fixed by the Catalog client in step 3, and
out of scope here.

**Not in this step, on purpose:** resolving the offer against Catalog, validating that it belongs to
the requested route, and freezing its terms. All three need the Catalog client and the fields
requested on #85, and they are step 3. Until then the column holds what the client claimed, which is
enough to persist and not enough to make a money decision on — worth a comment in the migration so
nobody mistakes it for a validated value.

**Done when:** ADR-015 is merged, a checkout carrying `offerId` persists it and one without it still
succeeds, `OrderView` exposes it, and #84 has been told.

---

## Step 2 — outcome

Branch `feature/orders-refund-adr-offer-id`. Full build green at **297 tests, 0 failures** (+8).

**ADR-015 written**, beside ADR-014 rather than instead of it: ADR-014 is Inventory's half (the
`CONFIRMED -> REFUNDED` transition that keeps Invariant 5), ADR-015 is the Orders half — who may
refund, in what order the reversal happens, and what state a partial failure leaves behind. It
records all six decisions listed for this step, and the four alternatives that were rejected, so
each one has a written reason rather than a habit: `CONFIRMED -> REFUNDING -> REFUNDED` with no
`REFUND_FAILED`, its own trigger and consumer, money first and seat second with
`SEAT_RETURN_FAILURE` left open, `pay-refund-{orderId}` as a separate idempotency namespace, the
cutoff evaluated with the Orders database clock, and a pre-cutoff replay that still returns its
original result after the cutoff.

**`offerId` persisted, unvalidated and nullable.** `V13__order_offer_id.sql`, the field on `Order`,
the write in the checkout transaction, and `OrderView`. The migration comment says in as many words
that the column holds what the client claimed and that no money decision may be made on it until the
Catalog client lands — the one thing a future reader is most likely to get wrong.

One decision that was not in the step text and that the fingerprint forced. `offerId` belongs in the
checkout fingerprint for the same reason `travelDate` does: it is part of what was ordered, so one
key reused for two offers must be an `IDEMPOTENCY_CONFLICT` rather than a replay of the other offer.
But changing the canonical string changes the hash of every request, and idempotency records live 24
hours — so during the deployment every key in flight would have answered 409 to its own retry. The
offer segment is therefore appended **only when the field is present**, which leaves an offer-less
payload byte-identical to what the old code hashed. Covered by
`a key stored before the offer field existed still replays`.

Tests: the offer reaching the order, a checkout without one still succeeding, the conflict, the
rollover replay, `OrderView` exposing it and omitting it, and a persistence round-trip proving V13
and the entity mapping agree.

**Not done here, deliberately:** nothing resolves the offer, so #93 (the client-supplied amount) is
still open and is step 3. Tightening `offerId` to required waits on #84.

Told Erestina on
[#84](https://github.com/g05-eurotransit/EuroTransit-application/issues/84#issuecomment-5619180337)
that the field exists and is accepted, with the two things it is not yet (not validated, not a price)
and the fingerprint rule for retries. The same comment closes step 1's last open item, which the
step 1 outcome did not record as done: item 3 is unblocked, the response shape is stated, and the
sweeper caveat travels with it.

Opened as [PR #94](https://github.com/g05-eurotransit/EuroTransit-application/pull/94), with the
fingerprint rollover called out as the thing to review.

---

## Step 3 — The Catalog client, price validation, and frozen refund terms

This is the step that turns `offerId` from a recorded string into something Orders can make a money
decision on. It closes **#93** and produces the inputs ADR-015's refund needs.

**Unblocked as of 2026-09-11.** #85 landed whole in PR #99 (`cf470d2`) and is closed: both the by-id
endpoint that 3a needs and the refund fields that 3b needs arrived together, so the split below is
now sequencing inside one step rather than a decision about scope.

What the merged contract gives, which is more than was asked for and changes 3b:
`GET /api/catalog/offers/{offerId}` returns `OfferDetail` carrying `price`, `currency`, `conditions`,
`refundPolicy(refundable, cutoffMinutesBeforeDeparture)`, `termsVersion`, **and the route's
`departureTime` and `departureZone` denormalized onto the offer**. So resolving one offer takes one
call: no second lookup on `/routes/{routeId}` to learn when the train leaves.

### 3a — Resolve the offer and validate the amount (needs only the by-id endpoint)

- **A Catalog client, in the synchronous checkout entry, not in the pipeline.** A wrong price must
  come back as a `400` on the checkout call, not as a `FAILED` order discovered minutes later: the
  customer can fix a rejected request, they cannot fix a failed one.
- **Its own Resilience4j instance** — `catalog-read`, registered separately, for exactly the reason
  step 1 separated `inventory-read`: a browsing-driven Catalog problem must not open a breaker the
  money path depends on. Tight timeout, no retry.
- **Fail closed.** If Catalog cannot be reached the checkout is rejected, not accepted on trust.
  This is a deliberate availability trade: Catalog becoming unavailable stops new checkouts. Say so
  in the ADR-015 follow-up or in a comment, because the tempting alternative — accept the client's
  amount when Catalog is down — reopens #93 exactly when nobody is watching.
- **Validate three things:** the offer exists, it belongs to the requested `routeId`, and its price
  matches `totalAmount`. A mismatch is a `400` with its own code, not a silent correction: silently
  charging a different amount than the client displayed is its own bug.
- **Persist the resolved price**, and treat `totalAmount` from the request as a consistency check
  only. The authorization sent to Payments must use the resolved value, which is the actual fix for
  #93 — `CheckoutOrchestrator:338` currently sends `ctx.order.totalAmount` straight from the request.
- **Take the currency from the offer too.** The line below the amount (`:339`) hardcodes
  `currency = "EUR"`, and `OfferDetail` carries a real `currency`. Latent today — all six seeded
  offers are EUR — but two of the routes are cross-border (Milano–Zurich, Torino–Lyon), so the first
  CHF offer would be authorized as euros at the same number. Resolve it with the price, persist it
  beside the amount, and send it to Payments. Same bug as #93, one field over.
- **The fingerprint stays as it is.** It hashes what the client asked for, and that is the right
  input for telling a retry from a key reuse; the resolved price is an output.

### 3b — Freeze the refund terms (needs the #85 fields)

- **Store the terms on the order, not just the version marker.** #86 asked for the offer version so
  a later catalogue edit cannot rewrite what a customer accepted — but a version only protects that
  if Catalog keeps history. **Now verified rather than assumed:** `InMemoryCatalogRepository:53`
  resolves with `offers.find { it.offerId == offerId }` over a flat list of current offers, and
  nothing anywhere keeps a previous `termsVersion`. So persist the resolved rule itself (refundable,
  cutoff duration) and keep the version beside it as an audit marker. This is a correction to the #86
  wording, not a re-litigation of it: same goal, and the version alone does not reach it.
- **Store the departure instant, computed once at checkout** as `travelDate + departureTime` in
  `departureZone`, as `TIMESTAMPTZ` like `reservation_expires_at`. Both fields come back on the same
  `OfferDetail`, so this is arithmetic on one response. The refund then compares one stored instant
  against the database clock, with no zone arithmetic on the refund path and no dependency on Catalog
  being reachable at refund time.
- **Migration `V14`**, and the fields on `Order` and — where the SPA needs them to decide whether to
  show a refund button — on `OrderView`.

### Tightening `offerId` to required

Price validation only bites for a checkout that carries an offer, so #93 is not fully closed while
the field is optional. That flip is gated on #84, not on Catalog. Land 3a validating when the field
is present, and flip to required as a one-line follow-up once the SPA sends it — recording in the
meantime that a checkout without `offerId` is still unvalidated, which is the honest state.

**Do one more thing in that same follow-up: make the offer segment of the checkout fingerprint
unconditional.** Step 2 appends it only when the field is present, so that an offer-less payload
hashes exactly as it did before the field shipped and keys in flight across the deployment still
replay. The cost is that the canonical string has a variable number of separators, so two logically
different requests can collide: a `paymentMethodToken` of `tok|of-001` with no offer produces the
same string as the token `tok` with offer `of-001`. Reachable only with a crafted token, and the
worst outcome is a replay of the caller's own order rather than a `409` — which is why it was left
alone rather than fixed with length prefixes and a dual-fingerprint transition. It disappears for
free the moment the field is mandatory, because every string then has fixed arity. Do not do it any
earlier: changing the canonical string while the field is optional is what the conditional append
exists to avoid, and it would break every idempotency key stored in the previous 24 hours.

### Cheap to fold in here: the past-date guard on the façade (#98)

Filed after this step was written. `GET /api/orders/seat-availability` forwards a `travelDate` in the
past to Inventory unchallenged, while `POST /api/orders` rejects one with `TRAVEL_DATE_IN_THE_PAST`.
One guard, no dependency on anything in this step, and it is already in a file this step touches.

It is only half of #98: the same-day departed-run filter needs the departure instant per route and is
the rest of that issue. Worth noting that the Catalog client built here is exactly what it will need,
so #98 becomes doable immediately after this step rather than blocked. #97 fixes the past-date case in
Inventory, which is where the seat map's own truth belongs; this guard is the second line of defence.

**Done when:** a checkout with a mismatched amount is rejected, the amount and currency sent to
Payments are the resolved ones, #93 is closed or explicitly reduced to the `offerId`-optional case,
and an order carries the refund rule and departure instant it was sold under.
