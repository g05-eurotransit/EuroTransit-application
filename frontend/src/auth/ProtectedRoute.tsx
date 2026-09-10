import { type PropsWithChildren, useEffect } from "react";
import { useAuth } from "react-oidc-context";
import { useLocation } from "react-router-dom";
import { useSilentSignInChecked } from "./SilentSignIn";
import type { PostLoginRedirect } from "./postLoginRedirect";


export function ProtectedRoute({ children }: PropsWithChildren) {
  const auth = useAuth();
  const silentChecked = useSilentSignInChecked();
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

  return <>{children}</>;
}
