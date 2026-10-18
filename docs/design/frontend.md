# Frontend (React SPA)

The EuroTransit frontend (`frontend/`) covers the assignment's required customer
flow: route/offer browsing, checkout, order-status refresh with clear success/failure
states, and live notifications. It also carries the two money-path screens that are not
customer-facing on their own — the operator console and the post-sale refund. Stack:
Vite + React 19 + TypeScript, served as static assets by nginx behind Traefik at the site
root (same origin as `/api/*`).

Every security decision is grounded in the course's **Frontend Security lecture (07b)**.

## Authentication

- **OIDC Authorization Code Flow + PKCE (S256)** against Keycloak, via `react-oidc-context`
  / `oidc-client-ts`. The SPA is a public client and holds no secret; PKCE proves the token
  request came from the client that started the flow (07b slides 14–16, RFC 9700).
- `state` param guards the callback against CSRF (slide 15).
- The browser redirect flow is used — **not** the password/direct-access grant (that was
  curl-only testing).

## Token storage

- **Access token in memory only** (`oidc-client-ts` configured with `InMemoryWebStorage`).
  Never `localStorage`/`sessionStorage` — any injected script could read it there (07b
  slides 5, 18, 19). The token is lost on reload; silent renew via the Keycloak SSO cookie
  restores the session without persisting the JWT.
- The non-React API client reads the token from an in-memory holder (`auth/tokenHolder.ts`)
  that a small component (`TokenSync`) keeps in sync — nothing touches persistent storage.

## Roles and shells

- The realm role is read from the **access** token's `realm_access.roles` (`auth/roles.ts`),
  not from the ID token: the access token is the one Orders authorizes on, and the local realm
  puts the claim in it alone (the cluster realm maps it into both).
- The claim is not verified in the browser and does not need to be. It decides what the UI
  *offers*; every endpoint behind it is gated server-side on `ROLE_operations`, so a forged
  claim buys a nav label and a page whose calls answer `403`.
- An `operations` account gets a different shell: `/operations` plus the order-detail page, and
  every other path redirects to the console. It cannot place an order (`POST /api/orders` is
  customer-only) and owns no tickets, so browsing and checkout would be dead ends.

## API calls

- Single `fetch` wrapper (`api/client.ts`): attaches `Authorization: Bearer <token>` on
  protected calls, a fresh `Idempotency-Key` per checkout attempt (reused only on retry of
  the same attempt — frozen contract), and a per-action `X-Correlation-Id` so the money-path
  trace ties back to the exact UI action.
- Relative same-origin `/api/...` paths → no CORS wildcard ever needed (07b slide 12).
- The frozen error model (`{code, message, correlationId}`) is mapped to typed UI outcomes:
  401→login, 403/404→not-authorized/not-found, 409→conflict, 422→business error,
  503→dependency/circuit-open.

## Checkout with a seat hold (ADR-016)

The checkout is two calls, minutes apart, and the seats are held between them.

- **Continue** (`SeatSelectionPage`) sends `POST /api/orders` **without** a payment token, which
  tells Orders to hold the seats and wait. The page goes to `/checkout?orderId=…`. The order id lives in
  the URL, so a reload resumes the same order rather than holding the seats a second time.
- Its `Idempotency-Key` is kept in `sessionStorage` per selection (route, date, seats) until Orders
  gives a definitive answer. It is kept on a network failure or a `5xx`, so the next click replays the
  same order, and dropped on success or on a refusal such as a `400`. This is a random request id,
  not a credential; the token-storage rule above is about the access token, which never goes there.
- **The payment page** (`CheckoutPage`) polls the order until it is `HELD_AWAITING_PAYMENT`, then
  counts down to `reservationExpiresAt` (T1, ten minutes).
  - The countdown is corrected by `serverNow`, so a wrong PC clock does not change it. It is
    presentation only: the server decides whether a payment is still accepted.
  - If the hold failed because the seats were taken, the customer goes back to the seat map with a
    message.
- **Pay now** creates the Stripe token and sends it to `POST /api/orders/{orderId}/payment`, then
  follows the order on `/orders/{orderId}` as before.
  - `HOLD_EXPIRED` → the same "time's up" message as the countdown reaching zero (below);
    `ORDER_NOT_PAYABLE` → back to the seats with a notice.
  - `PAYMENT_ALREADY_STARTED` (another tab paid) → the order page.
  - An unknown outcome (network, `5xx`) re-reads the order: if the payment got through, the page
    follows it; otherwise "try again" **resends the same key and the same Stripe token**. Orders
    fingerprints the token, so a fresh token under the old key would be refused rather than replayed.
- **Before saying "seats released"** the page checks that it really released them. A refused cancel is
  not proof of a payment. `409 ORDER_NOT_CANCELLABLE` answers both a payment that got in first (another
  tab, a lost response) and an order the expiry job already cancelled. So the page re-reads the order and
  goes to it only when its status is past the payment; otherwise back to the seats.
- **What releases the seats.** The page's own back button cancels the order. The countdown
  reaching zero also cancels it, so a new hold is possible at once instead of after the server's expiry job.
  It then shows a **"Time's up"** message in the middle of the screen, over a backdrop that blocks the
  form. After five seconds it goes back to the seat selection by itself; "Choose seats now" goes at once.
  Closing the tab, browser back or losing the network do **not**: browser exit signals are not
  reliable, so the hold simply runs out at T1.
- **One hold per customer.** A second Continue while a hold is waiting gets `409 ACTIVE_HOLD_EXISTS`. The
  seat page then offers **Resume** (the waiting order) or **Cancel it and continue**.
- **No second countdown.** After the payment the order page shows progress only. The deadline Orders
  keeps for the rest of the checkout (T2) is the pipeline's, not the customer's, and the UI never shows or
  names it. A held order opened from the order list shows **Complete payment**.

## SSE (live notifications)

- Uses `@microsoft/fetch-event-source`, **not** the native `EventSource`, specifically so
  the token travels in an `Authorization` header — the native `EventSource` can't set
  headers, which would force the JWT into the URL query string, forbidden by 07b slide 19
  (tokens leak in logs/history/Referer).
- On disconnect, the page keeps the history it already fetched and shows a soft "offline"
  state; checkout and order status are unaffected (graceful degradation — assignment).

## Operator console (`/operations`)

The UI over the reconciliation API (`docs/design/api-design.md`), for the orders automatic
recovery could not finish. Policy and the decision table live in
[orders-recovery-runbook.md](../operations/orders-recovery-runbook.md); the page only renders them.

- **One action per row, never four.** `allowedAction()` in `api/reconciliation.ts` duplicates the
  runbook's `kind` × `orderStatus` table and offers the single action that applies — or, where the
  runbook allows none, the reason instead of a button. The duplication is deliberate: the API
  refuses a wrong action with a `409` anyway, and an operator should not have to provoke the
  refusal to learn the rule. The four preconditions are mutually exclusive, so at most one of
  `redrive` / `abandon` / `settle` / `resolve` is ever accepted for a given incident.
- **The seat-hold clock is part of the decision.** A `PAYMENT_PENDING` order is redrivable until
  its hold lapses and abandonable only after; the table runs one 1 Hz clock and the button changes
  by itself at the deadline. The clock is shown only for `PAYMENT_PENDING` — every other status
  keeps the deadline its hold once had, necessarily in the past. The browser's clock is advisory:
  the server re-checks expiry in SQL against the database clock.
- **Every command is a `202`,** so the list reloads twice — immediately, then after a grace period
  — and that order's buttons stay disabled in between. Without it the reload lands while the
  pipeline is still working and can offer an action the order has already outgrown.
- **Evidence is required** for `settle` and `resolve` and is collected in a modal; the refusal
  codes are rendered as sentences, with the correlation id kept on screen.
- The list also refreshes itself every 30 s, paused while a dialog is open or a command is in
  flight.

## Post-sale refund

The customer path for ADR-015, on the order-detail page. The refund terms are frozen at checkout
and come back with the order (`refundable`, `refundCutoffMinutesBeforeDeparture`, `departureAt`),
so the page needs no catalogue call.

- `refundWindow()` in `api/orders.ts` mirrors the server's four refusals, so a customer reads the
  rule — "Refundable until 1 day before departure", "This fare is non-refundable" — instead of
  meeting a `409`. Eligibility is still decided server-side against the database clock.
- A refund is irreversible, so it is confirmed in a modal that states the amount, that the seat
  returns to sale, and that the ticket is void. A fresh `Idempotency-Key` per attempt keeps a
  double click from asking twice.
- `202` moves the order to `REFUNDING`, which is **not** terminal for the page: polling resumes
  until `REFUNDED`. The hold-expiry rule that stops polling is skipped for a refund — a completed
  sale has no hold left to run out.
- An `operations` account can refund on a customer's behalf, from the same page, reached by the
  order link in the console. The role lifts only the ownership check: the four eligibility rules
  apply to staff exactly as they do to the owner, so a refund outside the terms is refused for
  everyone.

## XSS

- Zero `dangerouslySetInnerHTML`; all backend strings (notification `message`, catalog
  fields) render as text through JSX escaping (07b slide 7). If HTML rendering is ever
  needed, it must go through `DOMPurify.sanitize`, and any URL used as `href`/`src` must be
  scheme-validated (no `javascript:`/`data:`).

## Secrets & supply chain

- No secrets in the frontend: `VITE_*` vars carry only the Keycloak issuer, the public
  client id, and the API base path — all public (07b slide 25). `.env.example` documents them.
- Minimal dependency footprint; `package-lock.json` committed; `npm audit` is clean for
  production dependencies (dev-only tooling advisories, if any, are tracked separately).

## Client-side validation

- UX only (07b slide 26 — "validate twice, enforce once"): the checkout form gives instant
  feedback, but the server is the source of truth for every rule.

## Production checklist (07b slide 29) — status

| Check | Status |
| --- | --- |
| Access token in memory; refresh token in httpOnly cookie | Access token in memory ✅. Refresh-token-in-httpOnly-cookie (BFF) noted as future hardening, out of scope for the local capstone. |
| Dangerous HTML wrapped in DOMPurify | N/A — no `dangerouslySetInnerHTML` used ✅ |
| CORS origin lists specific origins, not `*` | Same-origin via the gateway → no CORS needed ✅ |
| Session cookie `SameSite=Lax`/`Strict` + `Secure` | Keycloak session cookie — owned by Person 1 (gateway/Keycloak) ⛳ |
| CSP header present; no `unsafe-inline` in `script-src` | Set at the gateway (Traefik middleware); the build emits no inline scripts. Cross-slice item for Person 1 ⛳ |
| No secrets in `VITE_*` vars | ✅ |
| Client-side rules duplicated on server | ✅ (server enforces) |
| `npm audit` clean; lockfile committed; Dependabot | Lockfile committed, prod deps clean ✅; Dependabot is a repo-level follow-up ⛳ |

## Cross-slice items (owned by others)

- **Person 1 (gateway/security):** enforce PKCE (`pkce.code.challenge.method: S256`) on the
  Keycloak `eurotransit-frontend` client (currently unset); tighten `redirectUris`/`webOrigins`
  from `*` to the real frontend origin; add the Traefik CSP/HSTS/security-headers middleware
  on the frontend route (roll out `Content-Security-Policy-Report-Only` first — slide 23).
- **Person 4 (payments):** confirm the exact `paymentMethodToken` value/format the sandbox
  provider accepts (the UI sends the token Stripe.js creates from the card form).
