import { Badge, Button, Card } from "react-bootstrap";

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

export interface OrderSummaryCardProps {
  title?: string;
  origin: string;
  destination: string;
  departureTime: string;
  arrivalTime: string;
  trainType: string;
  fareClass: string;
  travelDate: string;
  seats: string[];
  totalAmount: number;
  currency: string;
  actionLabel: string;
  actionDisabled?: boolean;
  actionType?: "button" | "submit";
  onAction?: () => void;
}

export function OrderSummaryCard({
  title = "Order Summary",
  origin,
  destination,
  departureTime,
  arrivalTime,
  trainType,
  fareClass,
  travelDate,
  seats,
  totalAmount,
  currency,
  actionLabel,
  actionDisabled = false,
  actionType = "button",
  onAction,
}: OrderSummaryCardProps) {
  return (
    <Card className="border-2 border-primary shadow-sm sticky-top" style={{ top: "5rem" }}>
      <Card.Header className="bg-light fw-bold fs-4">{title}</Card.Header>
      <Card.Body>
        <div className="mb-3">
          <div className="text-uppercase text-secondary-emphasis fw-bold">Travel date</div>
          <div className="fs-5 fw-bold text-dark">{formatDate(travelDate)}</div>
        </div>
        <div className="mb-3">
          <div className="text-uppercase text-secondary-emphasis fw-bold">Departure</div>
          <div className="fs-5 fw-bold text-dark">{origin}</div>
          <div className="fs-5 fw-semibold text-dark">{departureTime.slice(0, 5)}</div>
        </div>
        <div className="mb-3">
          <div className="text-uppercase text-secondary-emphasis fw-bold">Arrival</div>
          <div className="fs-5 fw-bold text-dark">{destination}</div>
          <div className="fs-5 fw-semibold text-dark">{arrivalTime.slice(0, 5)}</div>
        </div>
        <hr />
        <div className="d-flex justify-content-between align-items-center mb-2">
          <span className="text-secondary-emphasis fw-semibold">Class</span>
          <span className="fs-5 fw-bold text-uppercase text-dark">{fareClass}</span>
        </div>
        <div className="d-flex justify-content-between align-items-center mb-2">
          <span className="text-secondary-emphasis fw-semibold">Train</span>
          <span className="fs-5 fw-bold text-dark">{trainType}</span>
        </div>
        <div className="d-flex justify-content-between align-items-start mb-2">
          <span className="text-secondary-emphasis fw-semibold">Seats</span>
          <span className="d-flex flex-wrap gap-2 justify-content-end">
            {seats.length === 0 ? (
              <span className="fst-italic text-secondary-emphasis">None</span>
            ) : (
              seats.map((s) => (
                <Badge
                  key={s}
                  bg="accent-subtle"
                  text="accent-emphasis"
                  className="border border-accent-subtle fs-6 fw-normal px-2 py-1"
                >
                  {s}
                </Badge>
              ))
            )}
          </span>
        </div>
        <hr />
        <div className="d-flex justify-content-between align-items-end mb-3">
          <span className="text-uppercase fw-bold text-secondary-emphasis">Total</span>
          <span className="fs-3 fw-bold text-accent">
            {totalAmount.toFixed(2)} {currency}
          </span>
        </div>
        <Button
          className="w-100"
          size="lg"
          type={actionType}
          disabled={actionDisabled}
          onClick={actionType === "button" ? onAction : undefined}
        >
          {actionLabel}
        </Button>
      </Card.Body>
    </Card>
  );
}
