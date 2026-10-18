import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Collapse } from "react-bootstrap";
import { listRoutes, listOffers, type Route, type Offer } from "../api/catalog";
import { ApiError } from "../api/client";
import { DateStrip } from "../components/controls/DateStrip";
import { MonthPicker } from "../components/controls/MonthPicker";
import { DAY_CODES, addDays, toISODate } from "../utils/dates";
import { arrivesNextDay, durationMinutes, formatDuration, hhmm, money } from "../utils/format";

type SortKey = "departure" | "price" | "duration";

const SORTS: { key: SortKey; label: string }[] = [
  { key: "departure", label: "Time" },
  { key: "price", label: "Price" },
  { key: "duration", label: "Duration" },
];

/** How far ahead "the next day with a train" is worth looking before giving up. */
const NEXT_RUNNING_DAY_LIMIT = 30;

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

  /** A weekday nothing runs on is closed in the calendar rather than being an empty page. */
  const isDayAvailable = useCallback(
    (date: Date) => {
      if (routes.length === 0) return true;
      return routes.some((r) => r.operatingDays.includes(DAY_CODES[date.getDay()]));
    },
    [routes],
  );

  /** The soonest day after this one that has a service, for the empty state's one useful action. */
  const nextRunningDay = useMemo(() => {
    for (let i = 1; i <= NEXT_RUNNING_DAY_LIMIT; i++) {
      const day = addDays(selectedDate, i);
      if (isDayAvailable(day)) return day;
    }
    return null;
  }, [selectedDate, isDayAvailable]);

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
    // A column of times: past ~860px the rows hollow out in the middle.
    <div className="mx-auto" style={{ maxWidth: 860 }}>
      <div className="d-flex flex-wrap justify-content-between align-items-end gap-3 mb-4">
        <div>
          <h1 className="fw-bold mb-2">Departures</h1>
          <div className="d-flex flex-wrap align-items-center gap-3">
            <MonthPicker selected={selectedDate} onSelect={setSelectedDate} isDayAvailable={isDayAvailable} />
            <span className="text-secondary-emphasis small">
              {sortedRoutes.length} {sortedRoutes.length === 1 ? "train" : "trains"}
            </span>
          </div>
          {error && <p className="text-danger mb-0 mt-2">{error}</p>}
        </div>
        <div className="d-flex align-items-center gap-2">
          <span className="et-lab">Sort by</span>
          <div className="et-seg" role="group" aria-label="Sort by">
            {SORTS.map((s) => (
              <button
                key={s.key}
                type="button"
                className={`et-seg__btn${sortBy === s.key ? " is-on" : ""}`}
                aria-pressed={sortBy === s.key}
                onClick={() => setSortBy(s.key)}
              >
                {s.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <DateStrip selected={selectedDate} onSelect={setSelectedDate} />

      {routes.length > 0 && sortedRoutes.length === 0 && (
        <div className="et-surface">
          <div className="et-edge">
            <div className="et-edge__ic">
              <i className="bi bi-train-front" aria-hidden="true" />
            </div>
            <h2>No trains on this day</h2>
            <p>Nothing runs on the date you picked. The timetable is not the same every day of the week.</p>
            {nextRunningDay && (
              <Button size="sm" className="fw-semibold" onClick={() => setSelectedDate(nextRunningDay)}>
                Go to{" "}
                {nextRunningDay.toLocaleDateString(undefined, {
                  weekday: "long",
                  day: "numeric",
                  month: "short",
                })}
              </Button>
            )}
          </div>
        </div>
      )}

      {/* Separate rows 8px apart: distinct objects to pick from, without a gap to cross. */}
      {sortedRoutes.length > 0 && (
        <div className="d-flex flex-column gap-2">
          {sortedRoutes.map((r) => {
            const fares = offersByRoute(r.routeId);
            const cheapest = cheapestPrice(r.routeId);
            const open = expandedRouteId === r.routeId;
            return (
              <div key={r.routeId} className="et-surface et-surface--quiet">
                <div className="et-route">
                  <div className="et-route__legs">
                    <div>
                      <div className="et-route__t">{hhmm(r.departureTime)}</div>
                      <div className="et-route__st">{r.origin}</div>
                    </div>
                    <div className="et-route__mid" aria-hidden="true">
                      <span className="small text-secondary-emphasis fw-semibold et-num">
                        {formatDuration(r.travelTime)}
                      </span>
                      <span className="et-route__wire" />
                      <span className="et-lab">{r.trainType}</span>
                    </div>
                    <div className="et-route__end">
                      <div className="et-route__t">{hhmm(r.arrivalTime)}</div>
                      <div className="et-route__st">{r.destination}</div>
                      {arrivesNextDay(r.departureTime, r.arrivalTime) && (
                        <div className="et-lab text-secondary mt-1">arrives +1 day</div>
                      )}
                    </div>
                  </div>

                  <div className="et-route__price">
                    <span className="et-lab">From</span>
                    <strong>{money(cheapest, fares[0]?.currency)}</strong>
                  </div>

                  <Button
                    variant="outline-primary"
                    className="rounded-3 p-0 d-flex align-items-center justify-content-center flex-shrink-0"
                    style={{ width: 36, height: 36 }}
                    aria-label={open ? "Hide fares" : "Show fares"}
                    aria-expanded={open}
                    onClick={() =>
                      setExpandedRouteId((current) => (current === r.routeId ? null : r.routeId))
                    }
                  >
                    <i className={`bi ${open ? "bi-chevron-up" : "bi-chevron-down"}`} aria-hidden="true" />
                  </Button>
                </div>

                <Collapse in={open}>
                  <div>
                    <div className="et-fares">
                      {fares.map((o) => (
                        <div
                          key={o.offerId}
                          // Marks the fare the row's "From" price refers to — pointless when it
                        // is the only fare on the route.
                        className={`et-fare${
                          fares.length > 1 && o.price === cheapest ? " et-fare--best" : ""
                        }`}
                        >
                          <div>
                            <div className="et-fare__cls">{o.fareClass}</div>
                            <div className="et-fare__cond">{o.conditions}</div>
                          </div>
                          <div className="et-fare__right">
                            <span className="et-fare__amt">{money(o.price, o.currency)}</span>
                            <Button size="sm" className="fw-semibold" onClick={() => selectOffer(r, o)}>
                              Select
                            </Button>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                </Collapse>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
