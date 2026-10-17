import { useAuth } from "react-oidc-context";

/** Keycloak realm roles, as the realm defines them (infrastructure/local/keycloak/eurotransit-realm.json). */
export const CUSTOMER_ROLE = "customer";
export const OPERATIONS_ROLE = "operations";

/** Reads a JWT payload without verifying it — safe only because of how it is used below. */
function jwtClaims(jwt: string | undefined): unknown {
  const payload = jwt?.split(".")[1];
  if (!payload) return null;
  const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
  try {
    const bytes = Uint8Array.from(atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), "=")), (c) =>
      c.charCodeAt(0),
    );
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
}

/** The `realm_access.roles` claim, or an empty list for any token that does not carry it. */
export function realmRoles(claims: unknown): string[] {
  if (!claims || typeof claims !== "object") return [];
  const realmAccess = (claims as Record<string, unknown>)["realm_access"];
  if (!realmAccess || typeof realmAccess !== "object") return [];
  const roles = (realmAccess as Record<string, unknown>)["roles"];
  if (!Array.isArray(roles)) return [];
  return roles.filter((role): role is string => typeof role === "string");
}

/**
 * True for a signed-in operations user — what switches the app into the operator UI (App.tsx).
 *
 * From the access token, not the ID token: it is the one Orders authorizes on, and the local realm
 * puts the roles claim in it alone. Unverified on purpose — this decides what the UI offers, never
 * what is allowed; every endpoint is gated on `ROLE_operations` by the Orders security chain.
 */
export function useIsOperator(): boolean {
  const auth = useAuth();
  return auth.isAuthenticated && realmRoles(jwtClaims(auth.user?.access_token)).includes(OPERATIONS_ROLE);
}
