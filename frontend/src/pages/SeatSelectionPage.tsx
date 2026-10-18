import { Fragment, useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Alert, Button, Col, Row, Spinner } from "react-bootstrap";
import { BackButton } from "../components/controls/BackButton";
import { OrderSummaryCard } from "../components/ticket/OrderSummaryCard";
import { arrivesNextDay, hhmm } from "../utils/format";
import { cancelOrder, createHold, getOrder, getSeatAvailability, isPaymentUnderway, type SeatView } from "../api/orders";
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
  const [cancelling, setCancelling] = useState(false);

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

  /** Selected is gold *and* filled, taken is grey *and* struck through: never colour alone. */
  function seatButton(seat: SeatView) {
    const selected = selectedSeats.includes(seat.seatId);
    return (
      <button
        key={seat.seatId}
        type="button"
        className={`et-seat${selected ? " is-selected" : ""}`}
        disabled={!seat.available}
        aria-pressed={selected}
        onClick={() => toggleSeat(seat.seatId)}
      >
        {seat.seatId}
      </button>
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
      } else if (e.kind === "network" || e.kind === "dependency" || (e.kind === "unknown" && e.status >= 500)) {
        // Unknown outcome: keep the key, so the next click replays this attempt rather than adding one.
        setHoldError("We could not reach the booking service. Please try again.");
      } else {
        // A definitive answer (a 400 such as a changed price, a 409 conflict): show it, and let the
        // next attempt start fresh.
        sessionStorage.removeItem(keyName);
        setHoldError(e.message);
      }
    } finally {
      setHolding(false);
    }
  }

  async function cancelWaitingHoldAndContinue() {
    if (!activeHoldOrderId || cancelling) return;
    setCancelling(true);
    try {
      await cancelOrder(activeHoldOrderId);
    } catch (e) {
      if (e instanceof ApiError && e.code === "ORDER_NOT_CANCELLABLE") {
        // The same answer covers a payment that started meanwhile and an order that simply expired.
        // Only the first must stop us: booking these seats too would be a second purchase.
        let underway: boolean | null = null;
        try {
          underway = isPaymentUnderway((await getOrder(activeHoldOrderId)).status);
        } catch {
          // Unknown: ask the customer to try again rather than guess.
        }
        if (underway === null) {
          setHoldError("We could not cancel your other checkout. Please try again.");
          return;
        }
        if (underway) {
          setActiveHoldOrderId(null);
          setHoldError("Your other checkout is already being paid. Check My orders before booking again.");
          return;
        }
      } else if (!(e instanceof ApiError && e.kind === "not_found")) {
        setHoldError("We could not cancel your other checkout. Please try again.");
        return;
      }
    } finally {
      setCancelling(false);
    }
    await continueToCheckout();
  }

  const freeSeats = seats?.filter((s) => s.available).length ?? null;

  return (
    <>
      <div className="d-flex align-items-center gap-3 mb-4">
        <BackButton />
        <h1 className="fw-bold mb-0">Choose your seat</h1>
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
            disabled={cancelling || holding}
            onClick={() => navigate(`/checkout?orderId=${encodeURIComponent(activeHoldOrderId)}`)}
          >
            Resume
          </Button>
          <Button
            variant="outline-dark"
            size="sm"
            disabled={cancelling || holding}
            onClick={() => void cancelWaitingHoldAndContinue()}
          >
            Cancel it and continue
          </Button>
        </Alert>
      )}

      <Row className="g-4">
        <Col lg={8}>
          <div className="et-surface">
            {/* A light header: the solid band belongs to the summary, on the right. */}
            <div className="et-maphead">
              <p className="et-maphead__sub">
                {offer.trainType} · <b>{hhmm(offer.departureTime)}</b> {offer.origin} →{" "}
                <b>{hhmm(offer.arrivalTime)}</b> {offer.destination}
                {arrivesNextDay(offer.departureTime, offer.arrivalTime) && " · +1 day"}
              </p>
              {freeSeats !== null && (
                <span className="badge rounded-pill bg-secondary-subtle text-primary border border-secondary-subtle">
                  {freeSeats} {freeSeats === 1 ? "seat" : "seats"} free
                </span>
              )}
            </div>

            <div className="et-legend">
              <span>
                <i style={{ background: "#fff", border: "1px solid var(--et-line)" }} /> Available
              </span>
              <span>
                <i className="bg-accent" /> Selected
              </span>
              <span>
                <i style={{ background: "var(--et-taken)", border: "1px solid var(--et-taken-line)" }} /> Taken
              </span>
            </div>

            {error && (
              <div className="p-3">
                <Alert variant="danger" className="mb-0">
                  {error}
                </Alert>
              </div>
            )}
            {!error && !seats && (
              <div className="text-secondary-emphasis d-flex align-items-center gap-2 p-4">
                <Spinner animation="border" size="sm" /> Loading seat map…
              </div>
            )}
            {!error && seats && (
              <div className="et-seatmap">
                {groupByRow(seats).map(({ row, seats: rowSeats }) => {
                  const aisleAt = Math.ceil(rowSeats.length / 2);
                  return (
                    <div key={row} className="et-seatmap__row">
                      <span className="et-seatmap__no">{row}</span>
                      {rowSeats.map((seat, i) => (
                        <Fragment key={seat.seatId}>
                          {i === aisleAt && <span className="et-seatmap__aisle" />}
                          {seatButton(seat)}
                        </Fragment>
                      ))}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
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
            unitPrice={offer.price}
            conditions={offer.conditions}
            totalAmount={total}
            currency={offer.currency}
            actionLabel={holding ? "Holding your seats…" : "Hold the seat and pay"}
            actionDisabled={selectedSeats.length === 0 || holding}
            actionType="button"
            onAction={() => void continueToCheckout()}
            // The button takes the seat off sale, so the rule is read before it is accepted.
            note={
              <>
                Confirming holds {selectedSeats.length > 1 ? "the seats" : "the seat"} for{" "}
                <b className="text-primary">10 minutes</b> — the time to pay. If it runs out the
                seat goes back on sale and you are not charged.
              </>
            }
          />
        </Col>
      </Row>
    </>
  );
}
