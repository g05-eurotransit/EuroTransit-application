import { useState } from "react";
import { Link, NavLink } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { Navbar, Nav, Container, Button, Dropdown, Badge } from "react-bootstrap";
import { useSilentSignInChecked } from "../../auth/SilentSignIn";
import { useIsOperator } from "../../auth/roles";
import { useNotifications, formatTimestamp, formatTrip } from "../../notifications/NotificationsProvider";
import { shortRef } from "../../utils/format";

const NAV_LINK_CLASS = "nav-link et-nav-link ms-4";
// The active page is marked by a gold rule under it, not by a colour change the eye would have to
// compare against its neighbour to notice.
const NAV_ACTIVE = "text-primary et-navlink-active";
const NAV_IDLE = "text-dark";
const NAV_LINK_STYLE = { fontWeight: 700, letterSpacing: "0.02em", fontSize: "1.05rem" };

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
  const isOperator = useIsOperator();
  const { items, unseenCount, markAllSeen } = useNotifications();
  // Frozen at open: marking them seen must not blank the rows you opened the menu to read.
  const [unseenAtOpen, setUnseenAtOpen] = useState(0);
  const displayName = auth.user?.profile.preferred_username ?? auth.user?.profile.email ?? "";

  return (
    <Navbar bg="white" expand="md" className="border-bottom sticky-top shadow-sm">
      {/* Edge to edge: the bar is the app's chrome, so the mark sits at the window's edge. */}
      <Container fluid className="py-2 px-4">
        {/* Two tones on one word, in the app's own two greens. */}
        <Navbar.Brand
          as={Link}
          to={isOperator ? "/operations" : "/"}
          className="text-primary fs-3 et-brand"
          aria-label="EuroTransit"
        >
          Euro<span className="text-secondary">Transit</span>
        </Navbar.Brand>
        <Navbar.Toggle aria-controls="main-nav" />
        <Navbar.Collapse id="main-nav">
          <Nav className="me-auto">
            {isOperator && (
              <span className="nav-link ms-4 text-primary" style={{ fontWeight: 700, letterSpacing: "0.02em", fontSize: "1.05rem" }}>
                Operations
              </span>
            )}
            {/* Departures is public, so it shows signed out too; `end` keeps "/" from always matching. */}
            {!isOperator && (
              <NavLink
                to="/"
                end
                className={({ isActive }) => `${NAV_LINK_CLASS} ${isActive ? NAV_ACTIVE : NAV_IDLE}`}
                style={NAV_LINK_STYLE}
              >
                Departures
              </NavLink>
            )}
            {auth.isAuthenticated && !isOperator && (
              <NavLink
                to="/orders"
                className={({ isActive }) => `${NAV_LINK_CLASS} ${isActive ? NAV_ACTIVE : NAV_IDLE}`}
                style={NAV_LINK_STYLE}
              >
                My tickets
              </NavLink>
            )}
          </Nav>
          <Nav className="align-items-center gap-3">
            {auth.isAuthenticated && !isOperator && (
              <Dropdown
                align="end"
                onToggle={(isOpen) => {
                  if (isOpen) {
                    setUnseenAtOpen(unseenCount);
                    markAllSeen();
                  }
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
                  <Dropdown.Header className="d-flex justify-content-between align-items-center gap-2">
                    <span className="fw-bold text-dark">Notifications</span>
                    {unseenAtOpen > 0 && (
                      <span className="et-lab">{unseenAtOpen} unread</span>
                    )}
                  </Dropdown.Header>
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
                        className={`d-flex gap-2 py-2 text-wrap ${
                          i < unseenAtOpen ? "et-notif--unseen " : ""
                        }${i < items.slice(0, 15).length - 1 ? "border-bottom" : ""}`}
                      >
                        {/* `type` is optional in the contract: an older notification has no marker. */}
                        {n.type && (
                          <span
                            className={`et-notif__mk ${
                              n.type === "ORDER_CONFIRMED" ? "et-notif__mk--ok" : "et-notif__mk--no"
                            }`}
                            aria-hidden="true"
                          >
                            <i
                              className={`bi ${
                                n.type === "ORDER_CONFIRMED" ? "bi-check-circle" : "bi-exclamation-triangle"
                              }`}
                            />
                          </span>
                        )}
                        <span className="flex-grow-1" style={{ minWidth: 0 }}>
                          {/* The message is a complete sentence on its own; the trip is extra. */}
                          {n.route && <span className="et-notif__trip d-block">{formatTrip(n.route)}</span>}
                          <span className="et-notif__msg d-block">{n.message}</span>
                          <span className="et-notif__foot">
                            <span className="et-notif__ref et-mono">#{shortRef(n.orderId)}</span>
                            <span className="et-notif__when">{formatTimestamp(n.occurredAt)}</span>
                          </span>
                        </span>
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
                  className="d-inline-flex align-items-center justify-content-center rounded-circle bg-secondary-subtle text-primary fw-bold"
                  style={{ width: 36, height: 36, fontSize: "0.8125rem" }}
                  title={displayName}
                >
                  {initials(displayName)}
                </span>
                <span
                  className="fw-semibold text-dark d-none d-lg-inline"
                  style={{ fontSize: "0.8125rem" }}
                >
                  {displayName}
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
