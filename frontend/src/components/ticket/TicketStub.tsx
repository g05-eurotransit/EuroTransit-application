import { QRCodeSVG } from "qrcode.react";
import type { OrderStatus } from "../../api/orders";

/** Sold: the seat was the customer's, so there is a real stub to print. */
function isSold(status: OrderStatus): boolean {
  return status === "CONFIRMED" || status === "REFUNDED";
}

/** A refunded ticket keeps its record but is void, and a gate would not let it through. */
function isValidForTravel(status: OrderStatus): boolean {
  return status === "CONFIRMED";
}

export interface TicketStubProps {
  status: OrderStatus;
  seats: string[];
  orderId: string;
}

/** The torn-off end of the ticket: printed once the seat is sold, a dashed outline before that. */
export function TicketStub({ status, seats, orderId }: TicketStubProps) {
  const seatLabel = seats.join(", ");

  if (!isSold(status)) {
    return (
      <>
        <div className="et-perf" />
        <div className="et-pending-stub">
          <div className="et-pending-stub__left">
            <span className="et-lab">{seats.length === 1 ? "Seat held" : "Seats held"}</span>
            <div className="et-pending-stub__big">{seatLabel}</div>
          </div>
          <div className="et-pending-stub__tx">
            <b>The ticket is printed here</b>
            We issue it once the payment is confirmed: until then the seat is held, not sold.
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="et-perf" />
      <div className="et-stub">
        <div>
          <span className="et-lab">{seats.length === 1 ? "Seat" : "Seats"}</span>
          <div className="et-stub__seat">{seatLabel}</div>
          <span className="et-lab d-block mt-3">Order reference</span>
          <div className="et-stub__id et-mono">{orderId}</div>
        </div>

        <div className="et-stub__qr">
          {isValidForTravel(status) ? (
            <>
              <QRCodeSVG
                value={orderId}
                size={104}
                level="M"
                marginSize={0}
                bgColor="#ffffff"
                fgColor="#15231c"
                title={`Order ${orderId}`}
              />
              <span className="et-lab">Scan at the gate</span>
            </>
          ) : (
            <>
              <span className="et-stub__void" aria-hidden="true">
                <i className="bi bi-slash-circle" />
              </span>
              <span className="et-lab">Not valid for travel</span>
            </>
          )}
        </div>
      </div>
    </>
  );
}
