# Customer Frontend (React SPA)

The EuroTransit customer frontend (`frontend/`) covers the assignment's required customer
flow: route/offer browsing, checkout, order-status refresh with clear success/failure
states, and live notifications. Stack: Vite + React 19 + TypeScript, served as static
assets by nginx behind Traefik at the site root (same origin as `/api/*`).

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

## API calls

- Single `fetch` wrapper (`api/client.ts`): attaches `Authorization: Bearer <token>` on
  protected calls, a fresh `Idempotency-Key` per checkout attempt (reused only on retry of
  the same attempt — frozen contract), and a per-action `X-Correlation-Id` so the money-path
  trace ties back to the exact UI action.
- Relative same-origin `/api/...` paths → no CORS wildcard ever needed (07b slide 12).
- The frozen error model (`{code, message, correlationId}`) is mapped to typed UI outcomes:
  401→login, 403/404→not-authorized/not-found, 409→conflict, 422→business error,
  503→dependency/circuit-open.

## SSE (live notifications)

- Uses `@microsoft/fetch-event-source`, **not** the native `EventSource`, specifically so
  the token travels in an `Authorization` header — the native `EventSource` can't set
  headers, which would force the JWT into the URL query string, forbidden by 07b slide 19
  (tokens leak in logs/history/Referer).
- On disconnect, the page keeps the history it already fetched and shows a soft "offline"
  state; checkout and order status are unaffected (graceful degradation — assignment).

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
  provider accepts (the UI currently sends `tok_visa`).
