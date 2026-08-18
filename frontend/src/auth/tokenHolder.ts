/**
 * Module-level holder for the current access token, kept in memory only.
 *
 * The API client (`src/api/client.ts`) is not a React component and can't call the
 * `useAuth()` hook, so a component syncs the token here whenever it changes. Nothing is
 * ever written to localStorage/sessionStorage (07b slides 18-19) — this variable lives in
 * the JS heap and is lost on reload, which is the intended behaviour.
 */
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}
