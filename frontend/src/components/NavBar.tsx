import { Link } from "react-router-dom";
import { useAuth } from "react-oidc-context";

export function NavBar() {
  const auth = useAuth();
  return (
    <nav className="nav">
      <Link to="/">EuroTransit</Link>
      <Link to="/notifications">Notifications</Link>
      <span className="spacer" />
      {auth.isAuthenticated ? (
        <>
          <span className="muted">{auth.user?.profile.preferred_username ?? "signed in"}</span>
          <button className="btn" onClick={() => void auth.signoutRedirect()}>
            Sign out
          </button>
        </>
      ) : (
        <button className="btn" onClick={() => void auth.signinRedirect()}>
          Sign in
        </button>
      )}
    </nav>
  );
}
