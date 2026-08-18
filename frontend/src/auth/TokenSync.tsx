import { useEffect } from "react";
import { useAuth } from "react-oidc-context";
import { setAccessToken } from "./tokenHolder";

/**
 * Keeps the in-memory token holder in sync with the current OIDC session, so the
 * non-React API client can attach the Bearer header. Renders nothing.
 */
export function TokenSync() {
  const auth = useAuth();

  useEffect(() => {
    setAccessToken(auth.isAuthenticated ? (auth.user?.access_token ?? null) : null);
  }, [auth.isAuthenticated, auth.user?.access_token]);

  return null;
}
