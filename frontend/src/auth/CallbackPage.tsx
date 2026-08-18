import { useEffect } from "react";
import { useAuth } from "react-oidc-context";
import { useNavigate } from "react-router-dom";

/**
 * OIDC redirect target. react-oidc-context (mounted app-wide) automatically processes the
 * `?code=&state=` response — validating `state` against CSRF (07b slide 15) and running the
 * PKCE code exchange. Once authenticated, bounce back to the home page.
 */
export function CallbackPage() {
  const auth = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (auth.isAuthenticated) navigate("/", { replace: true });
  }, [auth.isAuthenticated, navigate]);

  if (auth.error) return <p role="alert">Sign-in failed: {auth.error.message}</p>;
  return <p>Completing sign in…</p>;
}
