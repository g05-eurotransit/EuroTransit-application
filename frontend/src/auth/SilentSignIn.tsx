import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type PropsWithChildren,
} from "react";
import { useAuth } from "react-oidc-context";

const SilentSignInContext = createContext(false);

/** True once the initial silent-SSO check below has resolved (success, failure, or skipped). */
export function useSilentSignInChecked(): boolean {
  return useContext(SilentSignInContext);
}

/**
 * On first load, before any part of the app treats the user as signed out, try to restore the
 * session silently via the Keycloak SSO cookie (hidden iframe, no visible redirect).
 */
export function SilentSignInProvider({ children }: PropsWithChildren) {
  const auth = useAuth();
  const [checked, setChecked] = useState(false);
  const attempted = useRef(false);

  useEffect(() => {
    if (auth.isLoading || attempted.current) return;
    attempted.current = true;

    if (auth.isAuthenticated || auth.activeNavigator) {
      setChecked(true);
      return;
    }

    auth
      .signinSilent()
      .catch(() => {
        // No existing Keycloak SSO session (or it expired) — genuinely signed out, expected.
      })
      .finally(() => setChecked(true));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth.isLoading, auth.isAuthenticated, auth.activeNavigator, auth]);

  return <SilentSignInContext.Provider value={checked}>{children}</SilentSignInContext.Provider>;
}
