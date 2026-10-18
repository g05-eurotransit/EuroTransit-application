import { type PropsWithChildren, useEffect } from "react";
import { useAuth } from "react-oidc-context";
import { useLocation } from "react-router-dom";
import { useSilentSignInChecked } from "./SilentSignIn";
import { useIsOperator } from "./roles";
import type { PostLoginRedirect } from "./postLoginRedirect";

interface ProtectedRouteProps {
  /** Also require the `operations` realm role. Cosmetic: the real gate is the Orders chain. */
  requireOperator?: boolean;
}

export function ProtectedRoute({ children, requireOperator }: PropsWithChildren<ProtectedRouteProps>) {
  const auth = useAuth();
  const silentChecked = useSilentSignInChecked();
  const isOperator = useIsOperator();
  const location = useLocation();

  useEffect(() => {
    if (silentChecked && !auth.isAuthenticated && !auth.activeNavigator) {
      const redirect: PostLoginRedirect = {
        pathname: location.pathname,
        search: location.search,
        state: location.state as unknown,
      };
      void auth.signinRedirect({ state: redirect });
    }
  }, [silentChecked, auth.isAuthenticated, auth.activeNavigator, auth, location]);

  if (!silentChecked || auth.isLoading) return <p>Loading…</p>;
  if (auth.error) return <p role="alert">Authentication error: {auth.error.message}</p>;
  if (!auth.isAuthenticated) return <p>Redirecting to sign in…</p>;
  if (requireOperator && !isOperator) {
    return <p role="alert">This page is for operations accounts only.</p>;
  }

  return <>{children}</>;
}
