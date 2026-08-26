import { useEffect } from "react";
import { useAuth } from "react-oidc-context";
import { useNavigate } from "react-router-dom";
import { isPostLoginRedirect } from "./postLoginRedirect";

/**
 * OIDC redirect target. react-oidc-context (mounted app-wide) automatically processes the
 * `?code=&state=` response — validating `state` against CSRF (07b slide 15) and running the
 * PKCE code exchange. Once authenticated, bounce back to wherever ProtectedRoute sent the user
 * from (see its comment), falling back to the home page for plain sign-ins (e.g. the navbar
 * "Sign in" button, which doesn't set a custom state).
 */
export function CallbackPage() {
  const auth = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (!auth.isAuthenticated) return;
    const redirect = auth.user?.state;
    if (isPostLoginRedirect(redirect)) {
      navigate(
        { pathname: redirect.pathname, search: redirect.search },
        { replace: true, state: redirect.state },
      );
    } else {
      navigate("/", { replace: true });
    }
  }, [auth.isAuthenticated, auth.user, navigate]);

  if (auth.error) return <p role="alert">Sign-in failed: {auth.error.message}</p>;
  return <p>Completing sign in…</p>;
}
