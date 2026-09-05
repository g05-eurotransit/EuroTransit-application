import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button, Card, Col, Row } from "react-bootstrap";
import { BackButton } from "../components/BackButton";
import { OrderSummaryCard } from "../components/OrderSummaryCard";

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
}
const ROWS = [1, 2, 3];
const COLS = ["A", "B"];

export function SeatSelectionPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const state = location.state as OfferState | null;

  const [selectedSeats, setSelectedSeats] = useState<string[]>([]);

  useEffect(() => {
    if (!state) navigate("/", { replace: true });
  }, [state, navigate]);

  if (!state) return null;
  const offer: OfferState = state;

  function toggleSeat(seatId: string) {
    setSelectedSeats((prev) =>
      prev.includes(seatId) ? prev.filter((s) => s !== seatId) : [...prev, seatId],
    );
  }

  function seatButton(seatId: string) {
    const selected = selectedSeats.includes(seatId);
    return (
      <Button
        key={seatId}
        variant={selected ? "accent" : "outline-dark"}
        className="fw-bold fs-5 text-nowrap"
        style={{ width: "3.75rem", height: "3.5rem" }}
        onClick={() => toggleSeat(seatId)}
      >
        {seatId}
      </Button>
    );
  }

  const total = offer.price * (selectedSeats.length || 0);

  function continueToCheckout() {
    navigate("/checkout", {
      state: {
        ...offer,
        seats: selectedSeats,
        totalAmount: offer.price * selectedSeats.length,
      },
    });
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
          </div>

          <Card className="border-2 border-primary-subtle rounded-4 p-4">
            <div style={{ overflowX: "auto" }} className="pb-1">
              {ROWS.map((row) => (
                <div key={row} className="d-flex align-items-center gap-3 mb-3" style={{ width: "fit-content" }}>
                  <div className="text-secondary-emphasis fw-semibold fs-5" style={{ width: 28 }}>
                    {row}
                  </div>
                  <div className="d-flex gap-2">{COLS.map((c) => seatButton(`${row}${c}`))}</div>
                </div>
              ))}
            </div>
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
            actionLabel="Continue"
            actionDisabled={selectedSeats.length === 0}
            actionType="button"
            onAction={continueToCheckout}
          />
        </Col>
      </Row>
    </>
  );
}
