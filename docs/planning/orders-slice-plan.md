# Orders Slice: Working Plan

Working plan for the Orders slice owner. It is deliberately incomplete: only the current step and
the next one are written in detail. **When a step is finished, write the following step here before
starting it.** This keeps each step doable in a fresh session without reconstructing context.

Last updated: 2026-09-13.

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
| 3 | Refund part 2 — Catalog client and policy resolution | **done** — see the outcome below |
| 4 | Refund part 3 — refund state machine and endpoint | **next, detailed below** |
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

### Tightening `offerId` to required — now a step of its own, see below

Price validation only bites for a checkout that carries an offer, so #93 is not fully closed while
the field is optional. That flip is gated on #84, not on Catalog. Land 3a validating when the field
is present, and flip to required as a one-line follow-up once the SPA sends it — recording in the
meantime that a checkout without `offerId` is still unvalidated, which is the honest state.

**It stopped being a one-line follow-up on 2026-09-16.** #84 landed, and with it a defect in step 3
that only becomes visible once a client actually sends the field. The whole thing is written up as
*Step 3 follow-up* at the end of this file.

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

---

## Step 3 — outcome

Merged as `4b537cc` (PR #100), CI green. Full build green at **334 tests, 0 failures** (+32).

**The offer is resolved, and it is what the customer is charged.** `CatalogClient` /
`WebClientCatalogClient` on their own `catalog-read` breaker and bulkhead with no retry, called from
the synchronous checkout entry — never from the pipeline, so a wrong price is a `400` the customer
can fix rather than a `FAILED` order they find later. Three checks, each its own code:
`OFFER_NOT_FOUND`, `OFFER_ROUTE_MISMATCH`, `OFFER_PRICE_MISMATCH`. Amounts compare by value, so
`24.9` and `24.90` agree; a mismatch is refused and never silently corrected.

The resolved price and currency are persisted and `stageAuthorize` sends those, which is the actual
fix for **#93** — it read `ctx.order.totalAmount` straight from the request beside a hardcoded
`"EUR"`. Read off the row rather than re-resolved at pipeline time on purpose: the pipeline is
asynchronous and may resume much later, so a catalogue edit must not change what an accepted order
authorizes.

**Fail closed, stated as a cost rather than discovered as one.** Catalog unreachable is
`503 OFFER_UNRESOLVABLE`, not an acceptance on trust — a `503` and not a `400` because the request
was fine. While Catalog is down, checkouts *carrying an offer* stop; offer-less ones never touch
Catalog and keep working, which is asserted rather than assumed. Recorded in ADR-015 as an
amendment, because the tempting alternative reopens #93 silently.

**The refund terms are frozen, and the version marker turned out not to be enough.** #86 asked for
`termsVersion` so a later edit could not rewrite what a customer accepted; that only holds if Catalog
keeps history, and `InMemoryCatalogRepository:53` resolves against a flat list of current offers with
no previous revision stored anywhere. So `V14` persists the rule itself — `refundable`,
`refund_cutoff_minutes_before_departure` — with `offer_terms_version` beside it as an audit marker,
plus `departure_at` as `TIMESTAMPTZ`, composed once at checkout from `travelDate + departureTime` in
the station's zone. The refund then compares one stored instant against the database clock: no zone
arithmetic on the refund path and no Catalog call at refund time. Same migration adds `currency`
`NOT NULL`, backfilled and the default dropped as V9 does, so a future insert that forgets it fails
instead of quietly recording euros.

`OrderView` gains `currency`, `refundable`, `refundCutoffMinutesBeforeDeparture` and `departureAt`.
`termsVersion` is deliberately **not** exposed: it is an audit marker, and a client holding it might
use it as a key into a catalogue that no longer has that revision.

**One guard added after the step was written, and the reason is worth keeping.** Resolving the
offer made `currency` and `price` travel from Catalog into fields Payments validates —
`PaymentService` refuses a non-ISO-4217 currency and a non-positive amount with a `400`, and
`WebClientPaymentClient` treats only `DECLINED` as definitive. So that `400` would have become an
*unknown* outcome: the order parked in `PAYMENT_PENDING` holding a seat while the delivery retried to
the dead-letter topic. A poison message rather than a clean failure, and the poison would be
catalogue data. `resolveOffer` now checks the shape of both fields **before** the route and price
comparisons — the ordering matters, because a garbage price compared against the request would answer
`OFFER_PRICE_MISMATCH`, a `400` blaming the customer for a broken catalogue. It reuses
`OFFER_UNRESOLVABLE` rather than adding a fifth code to an error model `api-design.md` calls frozen;
the two cases are told apart by log level and metric tag, which is where an operator needs it.
Unreachable today — the six seeded offers are compile-time constants — but the *path* is new, and it
did not exist while the line was a literal `"EUR"`.

**#98's economic half is in**, as planned: the façade now refuses a past `travelDate` with
`TRAVEL_DATE_IN_THE_PAST`, the same code checkout uses, in front of the Inventory call rather than as
a filter on its answer. Same-day departed runs are the rest of that issue, and the Catalog client
built here is exactly what they need.

Tests worth naming: `an unreachable Catalog fails the checkout closed rather than trusting the
amount`, `the price that is persisted is the resolved one, not the one the client sent` (equal value,
different scale — it proves which number was stored), `a replay returns the stored result without
resolving the offer again` (resolution sits *after* the idempotency check, so an accepted order stays
replayable once Catalog goes down), `the authorization charges the amount and currency stored on the
order`, and `a failing offer lookup is attempted once, unlike the money path` — the missing retry is
an absence, and an absence can only be reviewed by asserting it.

**Deployment consequence, handled across both repositories.** Orders now reads
`eurotransit.services.catalog.url`. `application.yaml` and `docker-compose.yml` carry it, and
`EuroTransit-configuration` gained `EUROTRANSIT_SERVICES_CATALOG_URL` on the orders deployment with
`orders.services.catalogUrl` in `values.yaml` — merged there as `f54b04a` (configuration PR #33).
Without it, in-cluster Orders falls back to
`http://localhost:8081` and answers `503 OFFER_UNRESOLVABLE` to every offer checkout: harmless while
the SPA sends no `offerId`, and an outage on the day #84 lands, which is why it goes in before that
rather than with it. No NetworkPolicy change was needed — `allow-same-namespace` already permits
pod-to-pod ingress, and catalog's Service is the usual ClusterIP on 8080.

**Integrated, not deployed.** Both `ci.yml` and `docker-gitops.yml` trigger on a push to `main`, so
merging to `dev` builds no image and updates no GitOps tag. This reaches a cluster only when `dev` is
promoted to `main`; as of 2026-09-13 `dev` is 51 commits ahead of `main` and `main` 2 ahead of `dev`.
Configuration PR #33 is already on that repository's `main`, which is harmless in exactly the way the
PR described: the extra environment variable on an image that predates the change is ignored.

**Not done here, deliberately:** `offerId` is still optional, so #93 is closed for every checkout
that carries an offer and open for every checkout that does not. That flip is the one-line follow-up
below, gated on #84 and not on anything in this step.

**#93 and #98 were deliberately left open, each with a comment recording what landed and what
remains.** Neither is honestly closeable: #93 is unfixed for the traffic the system actually serves,
because the SPA sends no `offerId` yet; and #98's main ask — the same-day departed run — needs the
departure instant per route, which this step makes reachable but does not implement.

### The follow-up that closes #93 completely (one line, gated on #84)

Unchanged from what step 3 was written with, and repeated here so it is not lost: when the SPA sends
`offerId`, make it required **and** make the offer segment of the checkout fingerprint unconditional
in the same change. Step 2 appends it only when present, so an offer-less payload hashes as it did
before the field shipped; the cost is a canonical string with variable arity, where a
`paymentMethodToken` of `tok|of-001` with no offer collides with the token `tok` and offer `of-001`.
Reachable only with a crafted token and the worst outcome is a replay of the caller's own order, which
is why it was not worth length prefixes and a dual-fingerprint transition. It disappears for free once
every string has fixed arity. **Not a day earlier:** changing the canonical string while the field is
optional is exactly what the conditional append exists to avoid, and it would 409 every idempotency
key stored in the previous 24 hours.

---

## Step 4 — The refund: state machine, endpoint, and durable reversal

The last part of the refund, and the first that moves money backwards. ADR-015 has the decisions,
`V13`/`V14` have the offer and its frozen terms, and Inventory's post-sale transition is already
live. Blocked by nobody.

This step is written to be handed to someone — or something — that did not take the decisions. Hence
the two sections before the content: what to check before writing a line, and what may and may not
be re-decided while writing.

### Verified before writing — facts, not assumptions

Each of these was read in the code on 2026-09-13. They are here so the implementation does not
re-derive them, and so a wrong one is caught as a contradiction rather than absorbed as a surprise.

- **Inventory's endpoint exists and is not the shape the other two are.**
  `POST /api/inventory/reservations/{reservationId}/refund`, body `{orderId}`, returns
  `ReservationResponse` (`ReservationController:62`, `ReservationService:255`). It is idempotent **by
  state**: an already-`REFUNDED` reservation returns the same response instead of erroring
  (`ReservationService:269`), and it takes **no `Idempotency-Key` header**, unlike reserve and
  release. Do not invent one for it.
- **A confirmed order already carries everything the reversal needs.** `markConfirmed` clears
  `payment_method_token` but **keeps** `reservation_id` (`OrderRepository:84-90`), and
  `PaymentClient.refund(orderId, idempotencyKey)` takes no token. **No new column is needed for
  either call** — `V15` is only for the refund's own bookkeeping.
- **`OrderStatus.isTerminal` has five call sites and they do not all ask the same question.**
  `CheckoutOrchestrator:378` (may the `order-placed` consumer acknowledge without a remote call?) and
  `:398` (should the pipeline loop stop?), `ReconciliationOperationsService:68` and `:151`,
  `OrderPlacedDeadLetterListener:79`. **Read all five before touching it.** Both new states must
  answer *yes* to the checkout consumer, or a redelivered `order-placed` restarts a refunded sale —
  while the refund's own consumer needs the opposite answer for `REFUNDING`, which is exactly a state
  it must resume. Give the refund its own predicate; do not overload this one.
- **`runStage`'s `when` is exhaustive over `OrderStatus`**, so the new states will not compile until
  each has an arm. That is the forcing function, and the correct arm is the existing
  `error("unreachable: terminal states are filtered by the loop")` branch — not a new stage.
- **`ReconciliationKind` has three values today** — `DELIVERY_EXHAUSTED`, `INVARIANT_VIOLATION`,
  `PAYMENT_REVERSAL_FAILURE` (`ReconciliationRecord.kt:40`). `SEAT_RETURN_FAILURE` is new.
- **The database clock is read as `CURRENT_TIMESTAMP` in SQL**, as `ReconciliationRecordRepository`
  does throughout. Read the comment at `OrderRepository:178` first: it warns that comparing a plain
  `TIMESTAMP` against `CURRENT_TIMESTAMP` silently offsets by the session time zone. `departure_at`
  is `TIMESTAMPTZ` precisely so this comparison is safe — do not undo that by pulling the value into
  the JVM to compare it there.
- **A new outbox event type needs no connector change, but does need a topic.** Debezium routes by
  the `type` column (`route.by.field: type`, `route.topic.replacement: ${routedByValue}` in
  `platform/connectors/base/orders-outbox-connect.yaml`), so a new type lands on a topic of the same
  name automatically. But `auto.create.topics.enable: "false"`
  (`platform/strimzi/kafka-cluster.yaml:37`) and every topic is declared by hand in
  `platform/strimzi/kafka-topics.yaml`. **The new topic and its `-dlt` must be added in
  `EuroTransit-configuration` or the refund event is written to the outbox and goes nowhere** — a
  silent failure in the cluster and nowhere else.
- **Next migration number is `V15`.**
- **`feature/issue-70-readable-notifications` still has no PR** and rewrites the notification payload
  in `money-path-contracts` plus both emission points in `CheckoutOrchestrator`. If this step emits a
  refund notification, read that branch first rather than writing a third variant of the same thing.

### Settled, open, and stop-and-ask

**Settled — implement, do not re-litigate.** All from ADR-015: `CONFIRMED -> REFUNDING -> REFUNDED`
with deliberately no `REFUND_FAILED`; the refund's own outbox event and consumer, never the
`order-placed` pipeline; money reversed first and seat returned second; `pay-refund-{orderId}` as a
separate idempotency namespace; the cutoff evaluated against the Orders database clock; and a replay
of a pre-cutoff key returning its original result even after the cutoff.

**Open — decide while implementing, and write the decision down in the outcome.** The response shape
of the refund endpoint (probably `OrderView`, like `cancel`). Whether a refund emits a
`notification-requested` event, given the `#70` collision above. What `GET /api/orders/{id}` shows
while an order sits in `REFUNDING`. The exact names of the refusal codes.

**Stop and ask — do not decide alone.** Anything that changes what `isTerminal` means for the
checkout consumer. Any change to a public contract beyond adding refusal codes, since
`api-design.md` calls the error model frozen. Anything that needs a change inside `payments` or
`inventory` — those are other people's slices, and the answer is a message, not a commit.

**A standing check, regardless of what this plan says.** Before calling the step done, list every
value this change causes to cross a service boundary, and verify what the receiving service does
with it. Step 3 shipped a latent poison-message path precisely because nobody asked that question
until two days after the feature was written: Payments validates currency and amount and answers
`400`, and `WebClientPaymentClient:78` treats only `DECLINED` as definitive, so that `400` would have
parked an order in `PAYMENT_PENDING` holding a seat. The guard that fixed it was not in any plan.

### 4a — The states and `V15`

Add `REFUNDING` and `REFUNDED` to `OrderStatus` with the predicate split described above, then let
the compiler find the call sites. `V15` carries the refund's own bookkeeping: when it was requested,
when it completed, and the provider reversal reference. Keep the columns nullable — every order that
predates this step has none of them.

### 4b — The endpoint and the durable trigger

`POST /api/orders/{orderId}/refund`, owner or `operations`, mirroring `cancel`'s ownership rule: a
non-owner gets `404`, never `403`.

Refuse, each with its own code: the order is not `CONFIRMED`; it has no `offer_id` (nothing was sold
under terms — every order predating `V13` is in this set); `refundable` is false; or the request is
past `departure_at` minus `refund_cutoff_minutes_before_departure`, compared in SQL against
`CURRENT_TIMESTAMP`.

**The replay rule deserves its own test, because it is two opposite behaviours on one condition.** A
key accepted *before* the cutoff must still return its original result when the retry arrives
*after* it, while a new request after the cutoff is refused. The idempotency record is what
distinguishes them — `findIdempotentRequest` before the cutoff check, not after.

Commit `REFUNDING` and the outbox row in one transaction, exactly as checkout commits `ACCEPTED`
plus `order-placed` (`CheckoutOrchestrator` step 4 of `checkout`, and `orderOutboxRow`). Then a new
listener modelled on `OrderPlacedEventListener`: same correlation-id seeding into MDC and Reactor
context, same poison-versus-retryable distinction, same `orders.events.consumed{topic,result}`
counter.

### 4c — The reversal, in the order ADR-015 fixed

Money first. `PaymentClient.refund(orderId, "pay-refund-$orderId")` — **never** `refund-{orderId}`,
which checkout compensation already consumes and which would make Payments replay the compensation's
stored result: a `200` while no money moves.

Seat second. `InventoryClient` gains `refundReservation(reservationId, orderId)` beside
hold/confirm/release — on the money-path resilience chain, unlike step 3's read clients. If it fails
definitively the customer already has their money, so a `SEAT_RETURN_FAILURE` record stays open and
the order stays in `REFUNDING`. An unknown outcome from either call throws, leaves the row where it
is, and lets redelivery converge: no `catch`, exactly as the checkout pipeline.

### Cross-repository, and it must not be discovered late

The new topic and its `-dlt` in `platform/strimzi/kafka-topics.yaml`. Unlike step 3's catalog URL,
**this one has an ordering constraint**: without the topic, Debezium writes the outbox row and the
event is never delivered, so the refund silently never runs in the cluster while looking fine in
tests. Open that configuration PR alongside this step, not after it.

**Done when:** a confirmed refundable order inside its cutoff is refunded end to end — money
reversed, seat back to `AVAILABLE`, order `REFUNDED`; each refusal has its own code and a test; a
failed seat return leaves `REFUNDING` with an open reconciliation record rather than a terminal
state; the pre-cutoff replay returns its original result after the cutoff; and the standing
boundary-check above has been done and its result recorded.

### Then, and only then

- **#98's second half** — the same-day departed-run filter on the façade. It needs the departure
  instant per route; `GET /api/catalog/routes/{routeId}` already returns `departureTime` and
  `departureZone`, so the remaining question is only whether a departed run answers `404` or `200`
  with everything unavailable.
- **Step 5**, the Orders canary, whose two broken gates are already written up under "Findings that
  change the picture" with the attributable per-pod metrics to replace them.

---

## Step 3 follow-up — the price is per seat, and `offerId` becomes required

Step 3 validated the checkout amount against the offer and got the arithmetic wrong. The comparison
is between the offer's **unit** price and the request's **line total**, so **every checkout for two
or more seats is rejected** with `OFFER_PRICE_MISMATCH`. Live on `dev` since #84 landed on
2026-09-14; not deployed, because `main` has not been promoted.

The tests did not catch it because every offer-resolution case in `CheckoutOrchestratorTest` uses
exactly one seat, with `totalAmount` equal to the offer price. The bug is invisible at a quantity of
one.

### Verified before writing — facts, not assumptions

Read in the code on 2026-09-16. Three of these were not known when this step was first sketched.

- **The client sends the line total.** `SeatSelectionPage.tsx:107` sends
  `offer.price * selectedSeats.length`. Orders compares `offer.price` alone. The SPA's reading is the
  correct one: an offer is a fare, priced per seat.
- **The SPA always sends `offerId`, so making it required is safe.** `CheckoutState.offerId` is a
  non-optional `String`; `CheckoutPage` redirects to `/` without that navigation state; its only
  producer is `SeatSelectionPage.continueToCheckout()`, which spreads the whole offer. The API type
  says `offerId?: string`, but no path populates it as absent.
- **`offer_id` cannot become `NOT NULL`, and `V13:16` is wrong to say it will.** Orders placed before
  the SPA change legitimately hold `NULL`, so the migration would fail on the first one. The *API
  field* becomes required; the *column* stays nullable. Two different things.
- **Nothing validates that `seats` is non-empty.** Harmless today. It stops being harmless the moment
  the expected total is `price × seats.size`: an empty list makes the expected total `0`, and a
  request carrying `totalAmount: 0` would pass validation.
- **The fingerprint's offer segment is conditional** (`CheckoutOrchestrator:359`). It becomes
  unconditional in the same commit as the required field and never earlier — changing the canonical
  string while the field is optional answers `409` to every idempotency key stored in the previous
  24 hours.
- **`ASSUMED_CURRENCY` exists only for offer-less checkouts** and becomes unreachable.

### Settled, open, and stop-and-ask

**Settled.** The price is per seat. One branch. The arithmetic fix lands before the required flip.
The comment on #93 goes out after the merge, not before. Empty seats are refused with a new
`SEATS_REQUIRED` code — a client error deserves to be distinguishable from a wrong price, and the
addition to the frozen error model is accepted deliberately.

**Open — decide while implementing and record it.** Whether `ASSUMED_CURRENCY` is deleted or kept as
a defensive branch.

**Stop and ask.** Any change to the SPA or to Catalog. Making the column `NOT NULL`.

### Content, in order

1. **The comparison becomes `offer.price × seats.size`.** The refusal message must state the expected
   **total**, not the unit price — otherwise a customer buying two seats is told the price is 24.90.
2. **A guard on an empty seat list**, before resolution: `400 SEATS_REQUIRED`. Not orderable
   whatever the offer says.
3. **`offerId` required** on `CheckoutRequest`.
4. **The fingerprint's offer segment unconditional**, in the same commit as 3.
5. **`ASSUMED_CURRENCY` removed**, and `V13`'s comment corrected on the `NOT NULL` claim.
6. **`api-design.md`**: an offer price is per seat, `totalAmount` is `price × seats`, `offerId` is
   required.
7. **Tests**: two seats accepted; three seats priced as one rejected; an empty list rejected; a
   checkout without `offerId` rejected; and an idempotency replay that survives the canonical
   string's change of arity.

### Done when

A two-seat checkout with `totalAmount = 2 × price` succeeds end to end; the same checkout priced at
the unit price is refused; one without `offerId` is refused; the build is green. Plus one manual
two-seat purchase through the SPA — the case the automated tests were blind to.

### Standing check

Before closing: list every value this change causes to cross a boundary and verify what the receiver
does with it — **the caller included**. That is the verification step 3's plan did not have, and it
is why this defect shipped.

### Noted, not filed: is a price always per seat?

All six seeded offers are per seat and the SPA assumes it, so `price × seats` is correct today and is
written down as a constraint rather than left implicit. It would stop being correct for an offer sold
per unit rather than per passenger — `of-005` is a Roma–Wien sleeper, and a berth is sometimes sold
by compartment. If Catalog ever introduces a fixed-price or per-compartment offer, this multiplication
becomes wrong silently: the amount would simply be a multiple of what it should be. Deliberately not
filed as an issue — there is nothing to do until Catalog wants it — but it is the question to ask
giacomodipalma before any such offer is seeded.

---

# Handover — 2026-09-16

Written at the end of a long session, for whoever picks this up next (including me, in a fresh
session with none of that conversation). Everything below existed only in a chat transcript when it
was written, which is why it is here.

## Where things stand

Merged to `dev` today: the Catalog offer resolution follow-up (`7a1f7a6`, #107 — per-seat pricing and
a required `offerId`, closing #93) and the local Debezium outbox fix (`89d9afc`, #105). `dev` is green
at **347 tests**. Nothing is deployed: both `ci.yml` and `docker-gitops.yml` trigger on a push to
`main`, and `main` has not been promoted.

Issues closed today: #93, #83, #84, #81, #70. Still open: **#104** (mine, small — see below), **#98**
(the same-day departed-run filter), **#86** (the umbrella; it closes when the refund lands), **#72**
(deferred by decision, keep it open).

## The refund — step 4 is one step from done, on an unpushed branch

`feature/orders-refund`, **12 local commits, never pushed**, build green at **368 tests**. As of
today it is **22 commits behind `dev` with zero conflicts**, so rebasing is painless. Two rounds of
implementation and two adversarial reviews are already in it.

**Four corrections were decided and never applied.** They are the whole of what stands between that
branch and a pull request.

**F1 — a stuck refund's redrive is a guaranteed no-op, and the docs claim otherwise.** When a refund's
payment reversal is definitively declined, Payments stores an idempotency record for the failure
(`PaymentService`, the `FAILED` branch writes `responseCode = 422` and saves). A redrive re-sends the
same deterministic `pay-refund-{orderId}` key, Payments replays the stored `FAILED`, and the incident
re-opens unchanged. Nothing can close that record either: `closeAutoResolvable` only runs from
`markRefunded` (unreachable), `closeStaleFor`'s callers all exclude `REFUNDING`, and `resolve` is
filtered to `INVARIANT_VIOLATION`. The dead end pre-exists on the checkout side, so it is not a
regression — what the branch introduced is the *claim*, in `api-design.md` and in ADR-015, that
redrive is the operator's retry path for a stuck refund. For one of the three stuck states it is not.
**Minimum: remove the promise.** The real fix probably lives in `payments/` (do not cache a
provider-declined refund), which is a stop-and-ask.

**F2 — the third `isTerminal` reader was left alone.** `ReconciliationOperationsService.resolve`
guards on `!order.status.isTerminal` with a KDoc saying "so the money question is already settled".
`REFUNDING` now answers `true` to a gate written to mean the opposite. **Not reachable today** —
`resolve` only touches `INVARIANT_VIOLATION`, whose sole writer is `enterCompensating` inside the
`CONFIRMING_INVENTORY -> COMPENSATING` transaction, and `COMPENSATING`'s only SQL exit is `FAILED`,
so no order carrying one can reach `CONFIRMED`. Worth fixing anyway: it is one line, and it is the
last instance of the exact class of mistake the correction round existed to eliminate.

**F3 — the new gauge test cannot fail the way the query actually breaks.** For a `REFUNDING` row with
a null `refund_requested_at`, `CURRENT_TIMESTAMP - NULL` is `NULL`, `MAX` skips it, and `COALESCE`
yields **0** — so a stuck refund is counted but reports age zero forever and no age alert fires. The
test seeds `now() - 5s` and asserts `age >= 0 && age < 60`; zero satisfies that. Assert a lower bound
(`age > 1`) and add a case for a null column so the masking is a recorded decision.

**F4 — an integration test is named for the half it does not exercise.**
`redriving an already REFUNDED order is refused as terminal, and closes stale records` drives a clean
refund, so no reconciliation record is ever opened and `closeStaleFor` returns 0. It would pass
unchanged if that call were deleted. Force a `SEAT_RETURN_FAILURE` first, then assert the record is
`RESOLVED` after the refused redrive.

**Fold in with F1: refuse a redrive whose seat hold has expired.** Observed live today — Inventory's
sweeper expires the reservation and releases the seats, and Orders never learns: the order sits in
`PAYMENT_PENDING`/`REFUNDING` pointing at an `EXPIRED` reservation. The money is safe (Inventory
refuses the confirmation with `RESERVATION_EXPIRED`, Orders compensates), so the cost is a pointless
authorize-and-reverse cycle, not a hole. **Do not check `reservation_expires_at` before authorizing**
— that instant comes from Inventory's database clock, and comparing it against Orders' clock at the
boundary would throw away sales Inventory would still honour; Invariant 4 makes Inventory the only
authority. At redrive time hours have passed, the ambiguity is gone, and the operator gets an honest
refusal instead of triggering a charge and a refund with one click. F1 already touches `redrive`.

## Two things in the step 4 plan above that are now stale

**The `#70` collision note is obsolete.** It says to read `feature/issue-70-readable-notifications`
before writing a third notification. That branch is merged (`6ad3de2`, #106) and deleted. A refund
notification must be written against the payload as it is on `dev` now, not against that branch.

**`V13`'s promise to tighten `offer_id` to `NOT NULL` is wrong** and the correction is on the entity,
not in the migration. Editing an applied migration changes its Flyway checksum and breaks startup
everywhere it has already run — found the hard way today, by a local startup failing with
`Migration checksum mismatch for migration version 13` after a comment-only edit, on a green build.
The test suites cannot catch this: they always start from an empty database.

## Two defects found today that belong to someone else and are written down nowhere else

Both are for the frontend/payments owner (`eravreto`). Neither is blocking. Neither has an issue yet.

**Payments publishes to Kafka after the charge, with no outbox.** Observed: Stripe accepted the
charge, the event publish then failed, the endpoint answered `500`, and Orders classified it as an
unknown outcome — correctly, because it cannot know whether money moved. So a real charge can exist
with no order that knows about it. The specific publish bug that produced it is already fixed in the
source, but the structural question is untouched: Orders solved exactly this with an outbox row
written in the same transaction; Payments publishes directly.

**The order page never gives up.** `OrderStatusPage.poll()` polls every 1500 ms until the status is
terminal or `COMPENSATING`; `reservationExpiresAt` appears in no exit condition. When the hold
expires the seats are released and the order can no longer complete, but the page keeps polling an
outcome that will never arrive. Also, the countdown copy says the *payment* expires; it is the seat
hold, and #88 set that as an explicit copy constraint.

## Operational traps this session hit twice each, worth not repeating

**Stale local images.** `docker compose up -d` reuses an existing image. Two investigations were sent
down the wrong path by months-old images: once an endpoint that did not exist yet, once a bug already
fixed in source. Run `docker compose build <service>` before trusting any local reproduction.

**Green build, broken change.** Both defects that mattered today — the per-seat price and the Flyway
checksum — passed CI. The first because every test used one seat; the second because suites start
from an empty database. Neither would have been found without running the real thing by hand.

## Suggested order from here

1. Write the two reports above as issues, before they are lost.
2. Step 4: apply F1-F4 plus the redrive item, rebase on `dev`, open the PR. This closes #86.
3. #104 — map the unknown outcome from the seat-map façade to `503`. Small, self-contained; the issue
   body carries the full analysis and the measurements.
4. #98 — the same-day departed-run filter. The Catalog client makes it reachable;
   `GET /api/catalog/routes/{routeId}` already returns `departureTime` and `departureZone`.
5. Steps 5 and 6 of this plan, still unwritten.
