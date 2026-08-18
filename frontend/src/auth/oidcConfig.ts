import { WebStorageStateStore, InMemoryWebStorage } from "oidc-client-ts";
import type { AuthProviderProps } from "react-oidc-context";

const issuer = import.meta.env.VITE_KEYCLOAK_ISSUER as string;
const clientId = import.meta.env.VITE_KEYCLOAK_CLIENT_ID as string;

/**
 * OIDC configuration for the EuroTransit SPA.
 *
 * Security choices (course lecture 07b):
 * - Authorization Code Flow + PKCE S256 (slides 14-16): the SPA is a public client and
 *   holds NO secret; `response_type: "code"` + oidc-client-ts's built-in PKCE proves the
 *   token request came from the same client that started the flow.
 * - Access token kept in MEMORY only, never localStorage (slides 5, 18, 19): the userStore
 *   is an InMemoryWebStorage, so a stolen-XSS script cannot read the JWT from persistent
 *   storage, and the token is gone on reload (silent renew via the Keycloak SSO cookie
 *   restores the session without persisting the JWT).
 * - `state` param (default in oidc-client-ts) guards the callback against CSRF (slide 15).
 */
export const oidcConfig: AuthProviderProps = {
  authority: issuer,
  client_id: clientId,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  // The imported local realm exposes only the mandatory OpenID Connect scope.
  // Keep this request aligned with that contract; profile and email claims are
  // optional in the UI and their unavailable scopes make Keycloak reject login.
  scope: "openid",
  // In-memory token store — the JWT never touches localStorage/sessionStorage.
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  // Renew the access token silently before it expires, using the Keycloak SSO session
  // cookie, so reloads don't force a full redirect and the token stays short-lived.
  automaticSilentRenew: true,
  // Clean the ?code=&state= params out of the URL after a successful login.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};
