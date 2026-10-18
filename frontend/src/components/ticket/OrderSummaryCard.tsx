import type { ReactNode } from "react";
import { Button } from "react-bootstrap";
import { TripBand } from "./TripBand";
import { formatTravelDate } from "../../utils/dates";
import { money } from "../../utils/format";

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
  /** Per-seat price, so the summary can show where the total comes from. */
  unitPrice?: number;
  /** The fare's refund terms: this is what the customer accepts by paying. */
  conditions?: string;
  totalLabel?: string;
  actionLabel: string;
  actionDisabled?: boolean;
  actionType?: "button" | "submit";
  onAction?: () => void;
  /** The line under the button — what happens when it is pressed. */
  note?: ReactNode;
}

/** The summary column: the trip, what it costs, and the one button that commits to it. */
export function OrderSummaryCard({
  title = "Summary",
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
  unitPrice,
  conditions,
  totalLabel = "Total",
  actionLabel,
  actionDisabled = false,
  actionType = "button",
  onAction,
  note,
}: OrderSummaryCardProps) {
  return (
    <div className="et-surface sticky-top" style={{ top: "5rem" }}>
      <TripBand
        variant="slim"
        label={title}
        origin={origin}
        destination={destination}
        departureTime={departureTime}
        arrivalTime={arrivalTime}
      />

      <div className="et-sum">
        <div className="et-sum__r">
          <span className="et-sum__k">Travel date</span>
          <span className="et-sum__v">{formatTravelDate(travelDate)}</span>
        </div>
        <div className="et-sum__r">
          <span className="et-sum__k">Class</span>
          <span className="et-sum__v text-uppercase">{fareClass}</span>
        </div>
        <div className="et-sum__r">
          <span className="et-sum__k">Train</span>
          <span className="et-sum__v">{trainType}</span>
        </div>
        <div className="et-sum__sep" />
        <div className="et-sum__r">
          <span className="et-sum__k">{seats.length === 1 ? "Seat" : "Seats"}</span>
          <span className="d-flex flex-wrap gap-1 justify-content-end">
            {seats.length === 0 ? (
              <span className="fst-italic text-secondary-emphasis">None yet</span>
            ) : (
              seats.map((s) => (
                <span key={s} className="et-seatchip">
                  {s}
                </span>
              ))
            )}
          </span>
        </div>
        {/* Where the total comes from, without printing the total twice in one card. */}
        {unitPrice !== undefined && seats.length > 0 && (
          <div className="et-sum__r">
            <span className="et-sum__k">Per seat</span>
            <span className="et-sum__v et-num">
              {money(unitPrice, currency)} × {seats.length}
            </span>
          </div>
        )}
        {conditions && (
          <>
            <div className="et-sum__sep" />
            <div className="et-sum__r">
              <span className="et-sum__k">Conditions</span>
              <span className="et-sum__v fw-semibold" style={{ fontSize: "0.75rem", maxWidth: "22ch" }}>
                {conditions}
              </span>
            </div>
          </>
        )}
      </div>

      <div className="et-total">
        <div className="et-total__line">
          <span className="et-lab">{totalLabel}</span>
          <span className="et-total__amt">{money(totalAmount, currency)}</span>
        </div>
        <Button
          className="w-100 fw-semibold mt-3"
          size="lg"
          type={actionType}
          disabled={actionDisabled}
          onClick={actionType === "button" ? onAction : undefined}
        >
          {actionLabel}
        </Button>
        {note && <p className="et-total__note">{note}</p>}
      </div>
    </div>
  );
}
