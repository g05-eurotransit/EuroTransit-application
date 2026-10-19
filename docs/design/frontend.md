# Frontend

The React/TypeScript storefront supports browsing, seat selection, customer-held checkout, order progress, notifications and commercial refunds. Operations accounts use the reconciliation console. nginx serves the SPA and public runtime configuration behind Traefik; the UI calls same-origin APIs.

## Identity and client behavior

The public Keycloak client uses authorization code with PKCE. Access tokens remain in memory; silent renewal uses the SSO session. Roles from the access token select the UI shell, while services enforce the [authorization contract](api-design.md#security-boundary). An operations account lands on `/operations` and cannot purchase on behalf of a customer.

The shared fetch wrapper attaches the bearer token on protected calls and carries action correlation and idempotency identities. Tokens never belong in URLs, browser persistent storage, logs or runtime configuration. A random retry key may be stored in sessionStorage; it is not a credential.

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

## Notifications

Authenticated fetch-based SSE carries the token in an Authorization header. On disconnect, the UI retains fetched messages and can poll the notification API. Its [history limits](api-design.md#notifications) apply: the list is rebuilt from the topic after a restart, bounded by retention and the 50-entry cap, so displaying it does not establish durable history. Checkout and order progress remain usable while Notifications is unavailable.

## Operations and refunds

The console presents the single action applicable to an incident and order state, according to the [recovery runbook](../operations/orders-recovery-runbook.md). It rechecks state after asynchronous commands, disables in-flight actions and requires evidence for settle/resolve. Its deadline display is advisory; the backend checks the database clock.

Commercial refunds use frozen offer terms exposed by Orders. A successful acceptance shows refund progress rather than claiming money and seat return are already complete. API errors are rendered as actionable messages with correlation IDs; provider/internal failure details are never shown as customer explanations.

## Browser boundary

React escapes text; do not introduce untrusted HTML rendering. Runtime configuration is publicly retrievable and contains no secret. Client-side validation improves feedback but never replaces server validation, ownership or refund eligibility checks. See [frontend source](../../frontend/src) for routes and API consumers, and [local setup](../development/local.md) for execution.
