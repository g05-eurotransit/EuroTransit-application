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

**The availability façade is not a contested decision.** The design was stated on #68 and Inventory
implemented it to spec: the path, the `travelDate` parameter, and the collapse of `HELD`/`SOLD` into
a single `isAvailable` flag all match what was asked. The `api-design.md` row naming Catalog as the
caller is inconsistent with the code it documents in the same commit — a doc slip to correct, not a
counter-proposal to negotiate.

## Step list

| Step | What | Status |
| --- | --- | --- |
| 0 | Unblock everyone else: four messages, no code | **done** (2026-09-10) |
| 1 | Seat availability façade on Orders | **in progress** |
| 2 | Refund part 1 — `offerId` in the checkout contract | to be written when 1 lands |
| 3 | Refund part 2 — Catalog client and policy resolution | to be written |
| 4 | Refund part 3 — refund state machine and endpoint | to be written |
| 5 | Orders canary with metric-gated promote/abort | to be written |
| 6 | Chaos experiments, postmortem, agent log | to be written |

Steps 2 to 4 are one feature split by dependency, not three features. Step 3 needs #85 to have
landed; step 2 does not, which is why the request goes out in step 0.

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

**0.4 — Open points posted on #68.** The `404 ROUTE_NOT_FOUND` that conflates "unknown route" with
"sold out", and the missing filter for departed same-day seats that ADR-014 now returns to
`AVAILABLE`.

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
