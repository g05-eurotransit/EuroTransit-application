import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Alert, Button, Card, Col, Row, Spinner } from "react-bootstrap";
import { BackButton } from "../components/BackButton";
import { OrderSummaryCard } from "../components/OrderSummaryCard";
import { cancelOrder, createHold, getSeatAvailability, type SeatView } from "../api/orders";
import { ApiError, newId } from "../api/client";

interface OfferState {
  routeId: string;
  origin: string;
  destination: string;
  departureTime: string;
  arrivalTime: string;
  trainType: string;
  offerId: string;
  fareClass: string;
  price: number;
  currency: string;
  conditions: string;
  travelDate: string;
  /** Set by the checkout when it sends the customer back here: the seats were taken, time ran out. */
  notice?: string;
}

/**
 * The Idempotency-Key of one "Continue" for one selection, kept in sessionStorage until Orders
 * answers. A click after a lost response (or a reload in between) reuses it, so Orders replays the
 * same order instead of holding the seats twice; a different selection gets a key of its own.
 */
function holdKeyName(offer: OfferState, seats: string[]): string {
  return `hold-key:${offer.routeId}|${offer.travelDate}|${[...seats].sort().join(",")}`;
}

function holdKeyFor(name: string): string {
  const existing = sessionStorage.getItem(name);
  if (existing) return existing;
  const key = newId();
  sessionStorage.setItem(name, key);
  return key;
}

function groupByRow(seats: SeatView[]): { row: string; seats: SeatView[] }[] {
  const rows = new Map<string, SeatView[]>();
  for (const seat of seats) {
    const match = /^(\d+)([A-Za-z]+)$/.exec(seat.seatId);
    const row = match ? match[1] : "";
    if (!rows.has(row)) rows.set(row, []);
    rows.get(row)!.push(seat);
  }
  return [...rows.entries()]
    .sort(([a], [b]) => Number(a) - Number(b))
    .map(([row, rowSeats]) => ({
      row,
      seats: rowSeats.sort((a, b) => a.seatId.localeCompare(b.seatId, undefined, { numeric: true })),
    }));
}

export function SeatSelectionPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const state = location.state as OfferState | null;

  const [selectedSeats, setSelectedSeats] = useState<string[]>([]);
  const [seats, setSeats] = useState<SeatView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [holding, setHolding] = useState(false);
  const [holdError, setHoldError] = useState<string | null>(null);
  // ACTIVE_HOLD_EXISTS: the customer already has seats held for another checkout (ADR-016).
  const [activeHoldOrderId, setActiveHoldOrderId] = useState<string | null>(null);

  useEffect(() => {
    if (!state) navigate("/", { replace: true });
  }, [state, navigate]);

  useEffect(() => {
    if (!state) return;
    const ac = new AbortController();
    setSeats(null);
    setError(null);
    getSeatAvailability(state.routeId, state.travelDate, ac.signal)
      .then((res) => setSeats(res.seats))
      .catch((e) => {
        if (e instanceof ApiError && e.kind === "network") return; // aborted
        if (e instanceof ApiError && e.code === "RUN_ALREADY_DEPARTED") {
          setError("This service has already departed. Choose a later departure or a different date.");
          return;
        }
        if (e instanceof ApiError && e.kind === "not_found") {
          setError("No seats found for this route and date.");
          return;
        }
        setError(e instanceof Error ? e.message : "Failed to load seat availability");
      });
    return () => ac.abort();
  }, [state]);

  if (!state) return null;
  const offer: OfferState = state;

  function toggleSeat(seatId: string) {
    setSelectedSeats((prev) =>
      prev.includes(seatId) ? prev.filter((s) => s !== seatId) : [...prev, seatId],
    );
  }

  function seatButton(seat: SeatView) {
    const selected = selectedSeats.includes(seat.seatId);
    return (
      <Button
        key={seat.seatId}
        variant={selected ? "accent" : "outline-dark"}
        className="fw-bold fs-5 text-nowrap position-relative"
        style={{
          width: "3.75rem",
          height: "3.5rem",
          ...(!seat.available && { backgroundColor: "#e9ecef", borderColor: "#ced4da" }),
        }}
        disabled={!seat.available}
        onClick={() => toggleSeat(seat.seatId)}
      >
        {seat.seatId}
      </Button>
    );
  }

  // Multiply integer cents so floating-point tails never reach Orders' decimal price check.
  const total = (Math.round(offer.price * 100) * selectedSeats.length) / 100;

  async function continueToCheckout(retriedVanishedHold = false) {
    if (holding) return;
    setHolding(true);
    setHoldError(null);
    setActiveHoldOrderId(null);
    const keyName = holdKeyName(offer, selectedSeats);
    try {
      const res = await createHold(
        {
          routeId: offer.routeId,
          seats: selectedSeats,
          travelDate: offer.travelDate,
          totalAmount: total,
          offerId: offer.offerId,
        },
        holdKeyFor(keyName),
        newId(),
      );
      sessionStorage.removeItem(keyName);
      const { notice: _notice, ...offerOnly } = offer;
      navigate(`/checkout?orderId=${encodeURIComponent(res.orderId)}`, {
        state: { ...offerOnly, seats: selectedSeats, totalAmount: total, orderId: res.orderId },
      });
    } catch (e) {
      if (!(e instanceof ApiError)) {
        setHoldError("Unexpected error");
        return;
      }
      if (e.code === "ACTIVE_HOLD_EXISTS") {
        sessionStorage.removeItem(keyName);
        const waiting = typeof e.details?.orderId === "string" ? e.details.orderId : null;
        if (waiting) {
          setActiveHoldOrderId(waiting);
        } else if (!retriedVanishedHold) {
          // The blocking hold ended in the instant between the refusal and the lookup: just try again.
          setHolding(false);
          await continueToCheckout(true);
          return;
        } else {
          setHoldError(e.message);
        }
      } else if (e.kind === "network" || e.kind === "dependency" || e.kind === "unknown") {
        // Unknown outcome: keep the key, so the next click replays this attempt rather than adding one.
        setHoldError("We could not reach the booking service. Please try again.");
      } else {
        sessionStorage.removeItem(keyName);
        setHoldError(e.message);
      }
    } finally {
      setHolding(false);
    }
  }

  async function cancelWaitingHoldAndContinue() {
    if (!activeHoldOrderId) return;
    try {
      await cancelOrder(activeHoldOrderId);
    } catch {
      // Already paid, cancelled or expired: either way it no longer blocks a new hold.
    }
    await continueToCheckout();
  }

  return (
    <>
      <div className="d-flex flex-wrap justify-content-between align-items-center gap-3 mb-4 pb-3 border-bottom">
        <div>
          <div className="d-flex align-items-center gap-3">
            <BackButton />
            <h1 className="fw-bold mb-1">Choose your seat</h1>
          </div>
          <p
            className="text-uppercase text-secondary-emphasis fw-semibold mb-0"
            style={{ marginLeft: "3.5rem" }}
          >
            {offer.trainType} · {offer.fareClass}
          </p>
        </div>
      </div>

      {offer.notice && <Alert variant="warning">{offer.notice}</Alert>}
      {holdError && <Alert variant="danger">{holdError}</Alert>}
      {activeHoldOrderId && (
        <Alert variant="warning" className="d-flex flex-wrap align-items-center gap-3">
          <div className="flex-grow-1">
            You already have seats held for another checkout. Finish that one, or cancel it to book
            these seats instead.
          </div>
          <Button
            variant="primary"
            size="sm"
            onClick={() => navigate(`/checkout?orderId=${encodeURIComponent(activeHoldOrderId)}`)}
          >
            Resume
          </Button>
          <Button variant="outline-dark" size="sm" disabled={holding} onClick={() => void cancelWaitingHoldAndContinue()}>
            Cancel it and continue
          </Button>
        </Alert>
      )}

      <Row className="g-4">
        <Col lg={8}>
          <div className="d-flex gap-4 align-items-center bg-white border rounded-3 p-3 mb-4">
            <div className="d-flex align-items-center gap-2">
              <span
                className="d-inline-block rounded border"
                style={{ width: 22, height: 22, background: "var(--bs-white)" }}
              />
              <span className="fs-6 fw-semibold text-secondary-emphasis">Available</span>
            </div>
            <div className="d-flex align-items-center gap-2">
              <span className="d-inline-block rounded bg-accent" style={{ width: 22, height: 22 }} />
              <span className="fs-6 fw-semibold text-secondary-emphasis">Selected</span>
            </div>
            <div className="d-flex align-items-center gap-2">
              <span
                className="d-inline-block rounded border"
                style={{ width: 22, height: 22, backgroundColor: "#e9ecef", borderColor: "#ced4da" }}
              />
              <span className="fs-6 fw-semibold text-secondary-emphasis">Taken</span>
            </div>
          </div>

          <Card className="border-2 border-primary-subtle rounded-4 p-4">
            {error && <Alert variant="danger">{error}</Alert>}
            {!error && !seats && (
              <div className="text-secondary-emphasis d-flex align-items-center gap-2">
                <Spinner animation="border" size="sm" /> Loading seat map…
              </div>
            )}
            {!error && seats && (
              <div style={{ overflowX: "auto" }} className="pb-1">
                {groupByRow(seats).map(({ row, seats: rowSeats }) => (
                  <div key={row} className="d-flex align-items-center gap-3 mb-3" style={{ width: "fit-content" }}>
                    <div className="text-secondary-emphasis fw-semibold fs-5" style={{ width: 28 }}>
                      {row}
                    </div>
                    <div className="d-flex gap-2">{rowSeats.map((s) => seatButton(s))}</div>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>

        <Col lg={4}>
          <OrderSummaryCard
            title="Summary"
            origin={offer.origin}
            destination={offer.destination}
            departureTime={offer.departureTime}
            arrivalTime={offer.arrivalTime}
            trainType={offer.trainType}
            fareClass={offer.fareClass}
            travelDate={offer.travelDate}
            seats={selectedSeats}
            totalAmount={total}
            currency={offer.currency}
            actionLabel={holding ? "Holding your seats…" : "Continue"}
            actionDisabled={selectedSeats.length === 0 || holding}
            actionType="button"
            onAction={() => void continueToCheckout()}
          />
        </Col>
      </Row>
    </>
  );
}
