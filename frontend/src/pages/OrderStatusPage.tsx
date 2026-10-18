import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { Alert, Badge, Button, Card, Modal, Spinner } from "react-bootstrap";
import {
  cutoffLabel,
  getOrder,
  isTerminal,
  refundOrder,
  refundWindow,
  type OrderStatus,
  type OrderView,
} from "../api/orders";
import { newId } from "../api/client";
import { useIsOperator } from "../auth/roles";
import { getRoute, type Route } from "../api/catalog";
import { ApiError } from "../api/client";
import { OrderStepper, stepIndexOf } from "../components/OrderStepper";

const CURRENCY = "EUR";

/** A refund deadline: the day and the time, in the reader's own zone. */
function formatDeadline(ms: number): string {
  return new Date(ms).toLocaleString(undefined, {
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

/** The refund refusals (api-design.md), in a customer's words. */
const REFUND_REFUSAL_COPY: Record<string, string> = {
  ORDER_NOT_CONFIRMED: "This order can no longer be refunded.",
  ORDER_HAS_NO_OFFER: "This order was sold without refund terms.",
  OFFER_NOT_REFUNDABLE: "This fare is non-refundable.",
  REFUND_CUTOFF_PASSED: "The refund window for this ticket has closed.",
};

const FAILURE_COPY: Record<string, string> = {
  SEATS_UNAVAILABLE: "The seats you selected were taken by another customer first. Choose different seats and try again.",
  PAYMENT_DECLINED: "Your payment was declined. Try another payment method.",
};
const GENERIC_FAILURE_COPY = "This order could not be completed. Please try again.";

const HOLD_GRACE_MS = 10_000;
// Elapsed on the client's own clock, never against order.createdAt: that field is a LocalDateTime,
// so it is serialized with no offset and `new Date()` reads it as LOCAL time — east of UTC it lands
// in the past and this fires on the very first poll.
const NO_PROGRESS_MS = 10 * 60_000;

type PollStopReason = "deadline-passed" | "no-progress";

function pollStopReason(order: OrderView, watchingSinceMs: number): PollStopReason | null {
  // A refund has no seat hold left to run out — the sale completed — so only the no-progress rule
  // applies to it; without this exception every REFUNDING order stops on its stale hold deadline.
  if (order.reservationExpiresAt && order.status !== "REFUNDING") {
    const deadline = new Date(order.reservationExpiresAt).getTime();
    return Date.now() > deadline + HOLD_GRACE_MS ? "deadline-passed" : null;
  }
  return Date.now() - watchingSinceMs > NO_PROGRESS_MS ? "no-progress" : null;
}

const POLL_STOPPED_COPY: Record<PollStopReason, string> = {
  // Deliberately says nothing about a hold or its deadline: after the payment that deadline (T2) is the
  // pipeline's business, not the customer's (ADR-016), so the page never shows or names it.
  "deadline-passed":
    "This page will no longer update on its own. The order may still be resolving — refresh to " +
    "check its latest status.",
  "no-progress":
    "This order has not progressed since this page was opened, so this page will no longer update " +
    "on its own. The order may still be resolving — refresh to check its latest status.",
};

const STATUS_VARIANT: Record<OrderStatus, string> = {
  CONFIRMED: "success",
  FAILED: "danger",
  CANCELLED: "danger",
  ACCEPTED: "warning",
  RESERVING: "warning",
  HELD_AWAITING_PAYMENT: "warning",
  PAYMENT_PENDING: "warning",
  CONFIRMING_INVENTORY: "warning",
  COMPENSATING: "accent",
  REFUNDING: "accent",
  REFUNDED: "secondary",
};


/** What the tickets list sets when it links here, so this page can tell the two arrivals apart. */
interface OrderStatusNavState {
  fromTickets?: boolean;
}

export function OrderStatusPage() {
  const { orderId } = useParams<{ orderId: string }>();
  // The refund button belongs to the ticket detail, not to the receipt the checkout lands on; the
  // terms stay visible on both. History state, so a reload keeps it and the URL stays shareable.
  const fromTickets = (useLocation().state as OrderStatusNavState | null)?.fromTickets === true;
  const [order, setOrder] = useState<OrderView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [route, setRoute] = useState<Route | null>(null);
  const [lastKnownStepIndex, setLastKnownStepIndex] = useState(0);
  const [stopReason, setStopReason] = useState<PollStopReason | null>(null);
  const [rechecking, setRechecking] = useState(false);
  const [confirmingRefund, setConfirmingRefund] = useState(false);
  const [refunding, setRefunding] = useState(false);
  const [refundError, setRefundError] = useState<string | null>(null);
  // Bumped once a refund is accepted, to restart the poll loop that stopped at CONFIRMED.
  const [refundNonce, setRefundNonce] = useState(0);
  const isOperator = useIsOperator();

  useEffect(() => {
    if (!orderId) return;
    let stop = false;
    const ac = new AbortController();
    const watchingSince = Date.now();
    setLastKnownStepIndex(0); // new orderId: this session hasn't observed anything about it yet
    setStopReason(null);

    async function poll() {
      while (!stop) {
        try {
          const o = await getOrder(orderId!, ac.signal);
          if (stop) return;
          setOrder(o);
          const idx = stepIndexOf(o.status);
          if (idx >= 0) setLastKnownStepIndex((prev) => Math.max(prev, idx));
          if (isTerminal(o.status) || o.status === "COMPENSATING") return;
          const reason = pollStopReason(o, watchingSince);
          if (reason) {
            setStopReason(reason);
            return;
          }
        } catch (e) {
          if (e instanceof ApiError && e.kind === "network") return; // aborted
          if (e instanceof ApiError && e.kind === "not_found") {
            setError("Order not found or not visible to you.");
            return;
          }
          setError(e instanceof Error ? e.message : "Failed to load order");
          return;
        }
        await new Promise((r) => setTimeout(r, 1500));
      }
    }
    void poll();
    return () => {
      stop = true;
      ac.abort();
    };
  }, [orderId, refundNonce]);

  useEffect(() => {
    if (!order) return;
    const ac = new AbortController();
    getRoute(order.routeId, ac.signal)
      .then(setRoute)
      .catch(() => {}); // keep showing the raw routeId — see the comment on `route` above
    return () => ac.abort();
  }, [order?.routeId]);

  async function recheck() {
    if (!orderId || rechecking) return;
    setRechecking(true);
    try {
      const o = await getOrder(orderId);
      setOrder(o);
      if (isTerminal(o.status) || o.status === "COMPENSATING") setStopReason(null);
    } catch (e) {
      if (e instanceof ApiError && e.kind === "network") return; // aborted
      if (e instanceof ApiError && e.kind === "not_found") {
        setError("Order not found or not visible to you.");
        return;
      }
      setError(e instanceof Error ? e.message : "Failed to load order");
    } finally {
      setRechecking(false);
    }
  }

  async function requestRefund() {
    if (!orderId || refunding) return;
    setRefunding(true);
    setRefundError(null);
    try {
      // A fresh key per attempt: an accepted key replays its original answer, which is what keeps a
      // double click from asking for a second refund.
      const refunded = await refundOrder(orderId, newId(), newId());
      setOrder(refunded);
      setConfirmingRefund(false);
      setRefundNonce((n) => n + 1);
    } catch (e) {
      const text =
        e instanceof ApiError
          ? (e.code && REFUND_REFUSAL_COPY[e.code]) ?? e.message
          : e instanceof Error
            ? e.message
            : "The refund could not be requested.";
      setRefundError(text);
      setConfirmingRefund(false);
    } finally {
      setRefunding(false);
    }
  }

  if (error) return <p className="text-danger">{error}</p>;
  if (!order) return <p>Loading order…</p>;

  // Evaluated once per render: the page stops polling at CONFIRMED, so a window that lapses while
  // this sits open still shows its button. The server refuses it, and the refusal is displayed.
  const refund = refundWindow(order, Date.now());

  return (
    <div className="mx-auto" style={{ maxWidth: 640 }}>
      <h1 className="fw-bold mb-4">Order</h1>
      <Card className="border-0 shadow-sm">
        <Card.Body className="p-4">
          <div className="d-flex justify-content-between align-items-center mb-5">
            <div className="text-secondary-emphasis small">Order #{order.orderId}</div>
            <div className="d-flex align-items-center gap-3">
              <Badge bg={STATUS_VARIANT[order.status]} className="fs-6">
                {order.status}
              </Badge>
            </div>
          </div>

          <OrderStepper status={order.status} lastKnownStepIndex={lastKnownStepIndex} />

          <div className="d-flex flex-column gap-3 mb-4">
            <div>
              <div className="text-uppercase text-secondary-emphasis small fw-semibold">Route</div>
              <div className="fs-5 fw-bold text-dark">
                {route ? `${route.origin} → ${route.destination}` : order.routeId}
              </div>
            </div>
            <div>
              <div className="text-uppercase text-secondary-emphasis small fw-semibold">Travel date</div>
              <div className="fs-5 fw-bold text-dark">{formatDate(order.travelDate)}</div>
            </div>
            <div>
              <div className="text-uppercase text-secondary-emphasis small fw-semibold">Seats</div>
              <div className="fs-5 fw-bold text-dark">{order.seats.join(", ")}</div>
            </div>
            <div>
              <div className="text-uppercase text-secondary-emphasis small fw-semibold">Total</div>
              <div className="fs-5 fw-bold text-dark">
                {order.totalAmount.toFixed(2)} {order.currency ?? CURRENCY}
              </div>
            </div>
          </div>

          {order.status === "HELD_AWAITING_PAYMENT" && (
            <Alert variant="warning" className="d-flex flex-wrap align-items-center gap-3 mb-3">
              <div className="flex-grow-1">Your seats are held and waiting for your payment.</div>
              <Link
                to={`/checkout?orderId=${encodeURIComponent(order.orderId)}`}
                className="btn btn-primary btn-sm fw-semibold"
              >
                Complete payment
              </Link>
            </Alert>
          )}
          {!isTerminal(order.status) && order.status !== "COMPENSATING" && stopReason && (
            <Alert variant="warning" className="mb-0">
              <div className="d-flex align-items-start gap-2 mb-2">
                <i className="bi bi-clock-history fs-5" aria-hidden="true" />
                <div>{POLL_STOPPED_COPY[stopReason]}</div>
              </div>
              <Button
                variant="outline-dark"
                size="sm"
                disabled={rechecking}
                onClick={() => void recheck()}
              >
                <i className="bi bi-arrow-clockwise me-2" aria-hidden="true" />
                Refresh
              </Button>
            </Alert>
          )}
          {order.status === "COMPENSATING" && (
            <Alert variant="accent" className="d-flex align-items-center gap-2 mb-0">
              <i className="bi bi-hourglass-split fs-5" aria-hidden="true" />
              <div>
                We are resolving the payment for this order. This can take a few minutes — no
                further action is needed from you.
              </div>
            </Alert>
          )}
          {order.status === "CONFIRMED" && (
            <>
              <Alert variant="success" className="mb-3 fw-semibold">
                Your booking is confirmed.
              </Alert>
              {fromTickets && refundError && (
                <Alert variant="danger" className="mb-3" role="alert">
                  {refundError}
                </Alert>
              )}
              <div className="d-flex flex-wrap justify-content-between align-items-center gap-2">
                <div className="text-secondary-emphasis small">
                  {refund.open
                    ? `Refundable until ${cutoffLabel(order.refundCutoffMinutesBeforeDeparture)} — ${formatDeadline(refund.deadline)}.`
                    : refund.reason}
                </div>
                {fromTickets && refund.open && (
                  <Button
                    variant="outline-danger"
                    className="fw-semibold"
                    onClick={() => setConfirmingRefund(true)}
                  >
                    Refund this ticket
                  </Button>
                )}
              </div>
            </>
          )}
          {order.status === "REFUNDING" && (
            <Alert variant="accent" className="d-flex align-items-center gap-2 mb-0">
              <i className="bi bi-arrow-counterclockwise fs-5" aria-hidden="true" />
              <div>
                Refund in progress: the money is returned first, then the seat goes back on sale.
              </div>
            </Alert>
          )}
          {order.status === "REFUNDED" && (
            <Alert variant="secondary" className="mb-0 fw-semibold">
              This ticket was refunded.
            </Alert>
          )}
          {order.status === "FAILED" && (
            <Alert variant="danger" className="mb-0">
              {(order.failureCode && FAILURE_COPY[order.failureCode]) ?? GENERIC_FAILURE_COPY}
            </Alert>
          )}
          {order.status === "CANCELLED" && (
            <Alert variant="danger" className="mb-0">
              This order was cancelled.
            </Alert>
          )}
        </Card.Body>
      </Card>
      <div className="text-center mt-3">
        <Link to={isOperator ? "/operations" : "/"} className="btn btn-primary fw-semibold">
          <i className="bi bi-arrow-left me-2" aria-hidden="true" />
          {isOperator ? "Back to reconciliation" : "Back to routes"}
        </Link>
      </div>

      <Modal show={confirmingRefund} onHide={() => setConfirmingRefund(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title className="fs-5">Refund this ticket?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <p className="mb-0">
            {order.totalAmount.toFixed(2)} {order.currency ?? CURRENCY} goes back to the original
            payment method. The seat returns to sale and the ticket is void.
          </p>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="outline-secondary" onClick={() => setConfirmingRefund(false)}>
            Keep the ticket
          </Button>
          <Button
            variant="danger"
            className="fw-semibold"
            disabled={refunding}
            onClick={() => void requestRefund()}
          >
            {refunding ? (
              <>
                <Spinner animation="border" size="sm" />
                <span className="visually-hidden">Working…</span>
              </>
            ) : (
              "Refund"
            )}
          </Button>
        </Modal.Footer>
      </Modal>
    </div>
  );
}
