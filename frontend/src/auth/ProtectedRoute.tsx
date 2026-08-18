import { type PropsWithChildren, useEffect } from "react";
import { useAuth } from "react-oidc-context";

/**
 * Route guard (07b slide 17): if unauthenticated, trigger the Keycloak redirect login
 * instead of rendering the protected content. Public pages (browse) don't use this.
 */
export function ProtectedRoute({ children }: PropsWithChildren) {
  const auth = useAuth();

  useEffect(() => {
    if (!auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator) {
      void auth.signinRedirect();
    }
  }, [auth.isLoading, auth.isAuthenticated, auth.activeNavigator, auth]);

  if (auth.isLoading) return <p>Loading…</p>;
  if (auth.error) return <p role="alert">Authentication error: {auth.error.message}</p>;
  if (!auth.isAuthenticated) return <p>Redirecting to sign in…</p>;

  return <>{children}</>;
}
