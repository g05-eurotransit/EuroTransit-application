import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { listRoutes, listOffers, type Route, type Offer } from "../api/catalog";
import { ApiError } from "../api/client";

/** Public browse page: routes + offers, no auth required (catalog reads are public). */
export function BrowsePage() {
  const [routes, setRoutes] = useState<Route[]>([]);
  const [offers, setOffers] = useState<Offer[]>([]);
  const [error, setError] = useState<string | null>(null);
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

  return (
    <>
      <h1>Routes</h1>
      {error && <p className="error">{error}</p>}
      {routes.map((r) => (
        <div className="card" key={r.routeId}>
          <strong>
            {r.origin} → {r.destination}
          </strong>
          <div className="muted">
            {r.trainType} · {r.departureTime.slice(0, 5)}–{r.arrivalTime.slice(0, 5)}
          </div>
          <div>
            {offersByRoute(r.routeId).map((o) => (
              <div key={o.offerId} style={{ marginTop: "0.5rem" }}>
                {o.fareClass}: {o.price.toFixed(2)} {o.currency}{" "}
                <button
                  className="btn"
                  onClick={() =>
                    navigate("/checkout", {
                      state: { routeId: r.routeId, price: o.price, currency: o.currency },
                    })
                  }
                >
                  Buy
                </button>
              </div>
            ))}
          </div>
        </div>
      ))}
    </>
  );
}
