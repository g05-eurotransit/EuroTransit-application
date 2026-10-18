import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Badge, Button, Spinner } from "react-bootstrap";
import { listOrders, type OrderView } from "../api/orders";
import { getRoute, type Route } from "../api/catalog";
import { MONTHS_SHORT } from "../utils/dates";
import { durationMinutes, hhmm, money, shortRef } from "../utils/format";
import { ApiError } from "../api/client";
import { HoldPill } from "../components/ticket/HoldClock";
import { statusLabel } from "../status/labels";

/** The two reachable views: "My tickets" (confirmed only) and the unfiltered list. */
type ViewFilter = "CONFIRMED" | "ALL";

/** An order the customer still has to come back to: the row that carries a clock and a rule. */
function isLive(order: OrderView): boolean {
  return order.status === "HELD_AWAITING_PAYMENT";
}

/**
 * When the journey ends: a ticket is past once its train has arrived, not once it has left.
 * With no route resolved yet the day itself is the answer — wrongly past is worse than wrongly upcoming.
 */
function arrivalOf(order: OrderView, route: Route | undefined): number {
  if (!route) return new Date(`${order.travelDate}T23:59:59`).getTime();
  const departure = order.departureAt
    ? new Date(order.departureAt).getTime()
    : new Date(`${order.travelDate}T${route.departureTime}`).getTime();
  return departure + durationMinutes(route.travelTime) * 60_000;
}

export function OrdersListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filter: ViewFilter = searchParams.get("status") === "ALL" ? "ALL" : "CONFIRMED";

  const [orders, setOrders] = useState<OrderView[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // A list is searched by where it goes, not by order number, and OrderView carries only routeId.
  const [routes, setRoutes] = useState<Record<string, Route>>({});

  useEffect(() => {
    const ac = new AbortController();
    setLoading(true);
    setError(null);
    setOrders([]);
    setCursor(null);
    listOrders({ status: filter === "CONFIRMED" ? "CONFIRMED" : undefined }, ac.signal)
      .then((res) => {
        setOrders(res.orders);
        setCursor(res.nextCursor);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.kind === "network") return; // aborted
        setError(e instanceof Error ? e.message : "Failed to load orders");
      })
      .finally(() => setLoading(false));
    return () => ac.abort();
  }, [filter]);

  // One lookup per distinct route, only for the ones not already known.
  const missingRouteIds = useMemo(
    () => [...new Set(orders.map((o) => o.routeId))].filter((id) => !routes[id]),
    [orders, routes],
  );

  useEffect(() => {
    if (missingRouteIds.length === 0) return;
    const ac = new AbortController();
    void Promise.all(
      missingRouteIds.map((id) =>
        getRoute(id, ac.signal)
          .then((r) => [id, r] as const)
          .catch(() => null),
      ),
    ).then((pairs) => {
      const found = pairs.filter((p): p is readonly [string, Route] => p !== null);
      if (found.length > 0 && !ac.signal.aborted) {
        setRoutes((prev) => ({ ...prev, ...Object.fromEntries(found) }));
      }
    });
    return () => ac.abort();
  }, [missingRouteIds]);

  function loadMore() {
    if (!cursor) return;
    setLoadingMore(true);
    listOrders({ status: filter === "CONFIRMED" ? "CONFIRMED" : undefined, cursor })
      .then((res) => {
        setOrders((prev) => [...prev, ...res.orders]);
        setCursor(res.nextCursor);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load more orders"))
      .finally(() => setLoadingMore(false));
  }

  const confirmedCount = orders.filter((o) => o.status === "CONFIRMED").length;

  // Grouped on the page: `listOrders` pages by creation time, so this orders what is loaded.
  const { upcoming, past } = useMemo(() => {
    const now = Date.now();
    const withArrival = orders.map((o) => ({ order: o, arrival: arrivalOf(o, routes[o.routeId]) }));
    return {
      // Soonest first: the next trip is the one you came for.
      upcoming: withArrival
        .filter((r) => r.arrival >= now)
        .sort((a, b) => a.arrival - b.arrival)
        .map((r) => r.order),
      // Most recent first: a receipt is looked up backwards from today.
      past: withArrival
        .filter((r) => r.arrival < now)
        .sort((a, b) => b.arrival - a.arrival)
        .map((r) => r.order),
    };
  }, [orders, routes]);

  function row(o: OrderView, isPast: boolean) {
    const date = new Date(`${o.travelDate}T00:00:00`);
    const route = routes[o.routeId];
    const label = statusLabel(o.status);
    return (
      <Link
        key={o.orderId}
        to={`/orders/${o.orderId}`}
        // The order page shows the refund button only for an arrival from this list.
        state={{ fromTickets: true }}
        className={`et-surface et-surface--quiet et-tix${isLive(o) ? " et-tix--live" : ""}${
          isPast ? " et-tix--past" : ""
        }`}
      >
        <div className="et-tix__when">
          <div className="et-tix__dd">{date.getDate()}</div>
          <div className="et-tix__mo">{MONTHS_SHORT[date.getMonth()]}</div>
        </div>
        <div>
          <div className="et-tix__trip">
            {route ? `${route.origin} → ${route.destination}` : o.routeId}
          </div>
          <div className="et-tix__meta">
            {route && `${hhmm(route.departureTime)} · `}
            {o.seats.length === 1 ? "Seat" : "Seats"} {o.seats.join(", ")} ·{" "}
            <span className="et-mono">#{shortRef(o.orderId)}</span>
          </div>
        </div>
        <div className="et-tix__right">
          <span className="d-flex align-items-center gap-2">
            {isLive(o) && (
              <HoldPill expiresAt={o.reservationExpiresAt ?? null} clockOffsetMs={0} active />
            )}
            <Badge bg={label.bg} text={label.text}>
              {label.label}
            </Badge>
          </span>
          <span className="et-tix__amt">{money(o.totalAmount, o.currency)}</span>
        </div>
      </Link>
    );
  }

  return (
    <div className="mx-auto" style={{ maxWidth: 720 }}>
      {/* Same header as the departures: title and count left, the control that changes the list right. */}
      <div className="d-flex flex-wrap justify-content-between align-items-end gap-3 mb-4">
        <div>
          <h1 className="fw-bold mb-2">{filter === "CONFIRMED" ? "My tickets" : "All orders"}</h1>
          <span className="text-secondary-emphasis small">
            {filter === "CONFIRMED"
              ? `${orders.length} confirmed`
              : `${orders.length} orders · ${confirmedCount} confirmed`}
          </span>
        </div>
        <div className="d-flex align-items-center gap-2">
          <span className="et-lab">Show</span>
          <div className="et-seg" role="group" aria-label="Which orders to show">
            <button
              type="button"
              className={`et-seg__btn${filter === "CONFIRMED" ? " is-on" : ""}`}
              aria-pressed={filter === "CONFIRMED"}
              onClick={() => setSearchParams({ status: "CONFIRMED" })}
            >
              My tickets
            </button>
            <button
              type="button"
              className={`et-seg__btn${filter === "ALL" ? " is-on" : ""}`}
              aria-pressed={filter === "ALL"}
              onClick={() => setSearchParams({ status: "ALL" })}
            >
              All orders
            </button>
          </div>
        </div>
      </div>

      {error && <p className="text-danger">{error}</p>}
      {loading && (
        <div className="text-secondary-emphasis d-flex align-items-center gap-2">
          <Spinner animation="border" size="sm" /> Loading…
        </div>
      )}

      {!loading && !error && orders.length === 0 && (
        <div className="et-surface">
          <div className="et-edge">
            <div className="et-edge__ic">
              <i className="bi bi-receipt" aria-hidden="true" />
            </div>
            <h2>{filter === "CONFIRMED" ? "No tickets yet" : "No orders yet"}</h2>
            <p>When you buy a trip you will find it here, with its seat and its order reference.</p>
            <Link to="/" className="btn btn-sm btn-primary fw-semibold">
              See departures
            </Link>
          </div>
        </div>
      )}

      {/* Two groups: what is still to travel, then what has been. Headings only when both exist. */}
      {upcoming.length > 0 && (
        <>
          {past.length > 0 && (
            <div className="et-lab px-1 mb-2">Upcoming · {upcoming.length}</div>
          )}
          <div className="d-flex flex-column gap-2">{upcoming.map((o) => row(o, false))}</div>
        </>
      )}

      {past.length > 0 && (
        <>
          <div className={`et-lab px-1 mb-2${upcoming.length > 0 ? " mt-4" : ""}`}>
            Past · {past.length}
          </div>
          <div className="d-flex flex-column gap-2">{past.map((o) => row(o, true))}</div>
        </>
      )}

      {cursor && (
        <div className="text-center mt-3">
          <Button variant="outline-primary" disabled={loadingMore} onClick={loadMore}>
            {loadingMore ? <Spinner animation="border" size="sm" /> : "Load more"}
          </Button>
        </div>
      )}
    </div>
  );
}
