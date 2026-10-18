import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Card, Collapse, Form, Stack } from "react-bootstrap";
import { listRoutes, listOffers, type Route, type Offer } from "../api/catalog";
import { ApiError } from "../api/client";
import { DateStrip } from "../components/DateStrip";

type SortKey = "departure" | "price" | "duration";

function durationMinutes(iso: string): number {
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?$/.exec(iso);
  const hours = Number(match?.[1] ?? 0);
  const minutes = Number(match?.[2] ?? 0);
  return hours * 60 + minutes;
}

function formatDuration(iso: string): string {
  const total = durationMinutes(iso);
  const h = Math.floor(total / 60);
  const m = total % 60;
  return m ? `${h}h ${m}m` : `${h}h`;
}

const DAY_CODES = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];

function toISODate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

export function BrowsePage() {
  const [routes, setRoutes] = useState<Route[]>([]);
  const [offers, setOffers] = useState<Offer[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<SortKey>("departure");
  const [selectedDate, setSelectedDate] = useState(() => new Date());
  const [expandedRouteId, setExpandedRouteId] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    const ac = new AbortController();
    Promise.all([listRoutes(ac.signal), listOffers(ac.signal)])
      .then(([r, o]) => {
        setRoutes(r);
        setOffers(o);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.kind === "network") return; // aborted/offline
        setError(e instanceof Error ? e.message : "Failed to load catalog");
      });
    return () => ac.abort();
  }, []);

  const offersByRoute = (routeId: string) => offers.filter((o) => o.routeId === routeId);
  const cheapestPrice = (routeId: string) =>
    Math.min(...offersByRoute(routeId).map((o) => o.price), Number.POSITIVE_INFINITY);

  const sortedRoutes = useMemo(() => {
    const dayCode = DAY_CODES[selectedDate.getDay()];
    const now = new Date();
    // Only meaningful for today: a route on a future date can't have "already departed" yet.
    const isToday = selectedDate.toDateString() === now.toDateString();
    const nowTime = isToday
      ? `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}:${String(now.getSeconds()).padStart(2, "0")}`
      : null;
    const copy = routes.filter((r) => {
      if (!r.operatingDays.includes(dayCode)) return false;
      if (nowTime && r.departureTime <= nowTime) return false; // already departed today
      return true;
    });
    copy.sort((a, b) => {
      if (sortBy === "price") return cheapestPrice(a.routeId) - cheapestPrice(b.routeId);
      if (sortBy === "duration") return durationMinutes(a.travelTime) - durationMinutes(b.travelTime);
      return a.departureTime.localeCompare(b.departureTime);
    });
    return copy;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routes, offers, sortBy, selectedDate]);

  function selectOffer(route: Route, offer: Offer) {
    navigate("/checkout/seats", {
      state: {
        routeId: route.routeId,
        origin: route.origin,
        destination: route.destination,
        departureTime: route.departureTime,
        arrivalTime: route.arrivalTime,
        trainType: route.trainType,
        offerId: offer.offerId,
        fareClass: offer.fareClass,
        price: offer.price,
        currency: offer.currency,
        conditions: offer.conditions,
        travelDate: toISODate(selectedDate),
      },
    });
  }

  return (
    <div className="mx-auto" style={{ maxWidth: 920 }}>
      <div className="d-flex flex-wrap justify-content-between align-items-end mb-4 pb-3 border-bottom">
        <div>
          <h1 className="fw-bold mb-1">Available routes</h1>
          {error && <p className="text-danger mb-0">{error}</p>}
        </div>
        <Form.Group className="d-flex align-items-center gap-2">
          <Form.Label className="mb-0 small text-secondary-emphasis fw-semibold">Sort by</Form.Label>
          <Form.Select
            size="sm"
            style={{ width: "auto" }}
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as SortKey)}
          >
            <option value="departure">Departure time</option>
            <option value="price">Lowest price</option>
            <option value="duration">Duration</option>
          </Form.Select>
        </Form.Group>
      </div>

      <DateStrip selected={selectedDate} onSelect={setSelectedDate} />

      {routes.length > 0 && sortedRoutes.length === 0 && (
        <p className="text-secondary-emphasis text-center py-4">
          No routes operate on this day. Try a different date.
        </p>
      )}

      <Stack gap={3}>
        {sortedRoutes.map((r) => (
          <Card key={r.routeId} className="shadow-sm border-0 border-bottom">
            <Card.Body>
              <div className="d-flex flex-wrap justify-content-between align-items-center gap-3">
                <div className="d-flex flex-wrap align-items-center gap-3 gap-lg-5">
                  <div className="text-start" style={{ width: 180 }}>
                    <div className="fs-4 fw-bold">{r.departureTime.slice(0, 5)}</div>
                    <div className="text-uppercase text-secondary-emphasis fw-semibold">{r.origin}</div>
                  </div>
                  <div className="text-start" style={{ width: 130 }}>
                    <i className="bi bi-train-front fs-4 d-block text-accent" aria-hidden="true" />
                    <div className="small text-dark">
                      {formatDuration(r.travelTime)} · {r.trainType}
                    </div>
                  </div>
                  <div className="text-start" style={{ width: 180 }}>
                    <div className="fs-4 fw-bold">{r.arrivalTime.slice(0, 5)}</div>
                    <div className="text-uppercase text-secondary-emphasis fw-semibold">{r.destination}</div>
                  </div>
                </div>

                <div className="text-start">
                  <div className="text-uppercase text-secondary-emphasis small fw-semibold">From</div>
                  <div className="fs-4 fw-bold text-accent text-nowrap">
                    {cheapestPrice(r.routeId).toFixed(2)} {offersByRoute(r.routeId)[0]?.currency}
                  </div>
                </div>

                <Button
                  variant="outline-primary"
                  className="rounded-circle p-0 d-flex align-items-center justify-content-center flex-shrink-0"
                  style={{ width: 40, height: 40 }}
                  aria-label="Show fares"
                  onClick={() =>
                    setExpandedRouteId((current) => (current === r.routeId ? null : r.routeId))
                  }
                >
                  <i
                    className={`bi ${expandedRouteId === r.routeId ? "bi-chevron-up" : "bi-chevron-down"}`}
                    aria-hidden="true"
                  />
                </Button>
              </div>

              <Collapse in={expandedRouteId === r.routeId}>
                <div>
                  <Stack gap={2} className="border-top pt-3 mt-3">
                    {offersByRoute(r.routeId).map((o) => (
                      <div
                        key={o.offerId}
                        className="d-flex justify-content-between align-items-center border rounded-2 px-3 py-2"
                      >
                        <div>
                          <div className="fw-bold text-uppercase">{o.fareClass}</div>
                          <div className="text-secondary-emphasis" style={{ fontSize: "0.9rem" }}>
                            {o.conditions}
                          </div>
                        </div>
                        <div className="d-flex align-items-center gap-3">
                          <span className="fw-bold text-accent fs-5">
                            {o.price.toFixed(2)} {o.currency}
                          </span>
                          <Button className="fw-semibold" onClick={() => selectOffer(r, o)}>
                            Buy
                          </Button>
                        </div>
                      </div>
                    ))}
                  </Stack>
                </div>
              </Collapse>
            </Card.Body>
          </Card>
        ))}
      </Stack>
    </div>
  );
}
