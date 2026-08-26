import { Link } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { Navbar, Nav, Container, Button, Dropdown, Badge } from "react-bootstrap";
import { useSilentSignInChecked } from "../../auth/SilentSignIn";
import { useNotifications, formatTimestamp, shortOrderRef } from "../../notifications/NotificationsProvider";

/** First letters of a display name/email, for the avatar circle (no profile photo exists). */
function initials(nameOrEmail: string): string {
  const trimmed = nameOrEmail.trim();
  if (!trimmed) return "?";
  const parts = trimmed.split(/[\s@.]+/).filter(Boolean);
  return parts
    .slice(0, 2)
    .map((p) => p[0]!.toUpperCase())
    .join("");
}

export function NavBar() {
  const auth = useAuth();
  const silentChecked = useSilentSignInChecked();
  const { items, unseenCount, markAllSeen } = useNotifications();
  const displayName = auth.user?.profile.preferred_username ?? auth.user?.profile.email ?? "";

  return (
    <Navbar bg="white" expand="md" className="border-bottom sticky-top shadow-sm">
      <Container fluid className="py-2 px-4">
        <Navbar.Brand as={Link} to="/" className="fw-bold text-primary fs-3">
          EuroTransit
        </Navbar.Brand>
        <Navbar.Toggle aria-controls="main-nav" />
        <Navbar.Collapse id="main-nav" className="justify-content-end">
          <Nav className="align-items-center gap-3">
            {auth.isAuthenticated && (
              <Dropdown
                align="end"
                onToggle={(isOpen) => {
                  if (isOpen) markAllSeen();
                }}
              >
                <Dropdown.Toggle
                  as="button"
                  bsPrefix="notif-bell-toggle"
                  className="btn p-0 border-0 bg-transparent position-relative"
                  title="Notifications"
                >
                  <i className="bi bi-bell fs-4" aria-hidden="true" />
                  <span className="visually-hidden">Notifications</span>
                  {unseenCount > 0 && (
                    <Badge
                      bg="accent"
                      pill
                      className="position-absolute top-0 start-100 translate-middle fs-6"
                      style={{ fontSize: "0.65rem" }}
                    >
                      {unseenCount > 9 ? "9+" : unseenCount}
                    </Badge>
                  )}
                </Dropdown.Toggle>

                <Dropdown.Menu
                  style={{ width: 340, maxWidth: "90vw", maxHeight: 420, overflowY: "auto" }}
                >
                  <Dropdown.Header>Notifications</Dropdown.Header>
                  {items.length === 0 ? (
                    <div className="px-3 py-4 text-center text-secondary-emphasis small">
                      No notifications yet.
                    </div>
                  ) : (
                    items.slice(0, 15).map((n, i) => (
                      <Dropdown.Item
                        key={n.id}
                        as={Link}
                        to={`/orders/${n.orderId}`}
                        className={`py-2 text-wrap ${i < items.slice(0, 15).length - 1 ? "border-bottom" : ""}`}
                      >
                        <div className="small text-dark">{n.message}</div>
                        <div className="d-flex justify-content-between align-items-center mt-1">
                          <span className="text-accent fw-semibold" style={{ fontSize: "0.75rem" }}>
                            Order #{shortOrderRef(n.orderId)}
                          </span>
                          <span className="text-secondary-emphasis" style={{ fontSize: "0.75rem" }}>
                            {formatTimestamp(n.occurredAt)}
                          </span>
                        </div>
                      </Dropdown.Item>
                    ))
                  )}
                </Dropdown.Menu>
              </Dropdown>
            )}
            {/* Hold off on any signed-out UI (e.g. a "Sign in" button that would immediately
                flip to "Sign out") until the silent SSO-cookie check has had a chance to run —
                see SilentSignIn.tsx. */}
            {!silentChecked ? null : auth.isAuthenticated ? (
              <>
                <span
                  className="d-inline-flex align-items-center justify-content-center rounded-circle bg-secondary-subtle text-primary fw-bold fs-6"
                  style={{ width: 44, height: 44 }}
                  title={displayName}
                >
                  {initials(displayName)}
                </span>
                <Button variant="outline-primary" className="fw-semibold" onClick={() => void auth.signoutRedirect()}>
                  Sign out
                </Button>
              </>
            ) : (
              <Button variant="primary" className="fw-semibold" onClick={() => void auth.signinRedirect()}>
                Sign in
              </Button>
            )}
          </Nav>
        </Navbar.Collapse>
      </Container>
    </Navbar>
  );
}
