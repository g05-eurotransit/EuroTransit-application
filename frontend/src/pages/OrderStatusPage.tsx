import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Alert, Badge, Button, Card, Spinner } from "react-bootstrap";
import { getOrder, isTerminal, type OrderStatus, type OrderView } from "../api/orders";
import { getRoute, type Route } from "../api/catalog";
import { ApiError } from "../api/client";
import { HAPPY_PATH, OrderStepper } from "../components/OrderStepper";
import { useCountdown } from "../hooks/useCountdown";

const CURRENCY = "EUR";

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

const FAILURE_COPY: Record<string, string> = {
  SEATS_UNAVAILABLE: "The seats you selected were taken by another customer first. Choose different seats and try again.",
  PAYMENT_DECLINED: "Your payment was declined. Try another payment method.",
};
const GENERIC_FAILURE_COPY = "This order could not be completed. Please try again.";

const HOLD_GRACE_MS = 10_000;
const HOLD_MAX_MS = 10 * 60_000;

type PollStopReason = "hold-expired" | "no-progress";

function pollStopReason(order: OrderView): PollStopReason | null {
  if (order.reservationExpiresAt) {
    const deadline = new Date(order.reservationExpiresAt).getTime();
    return Date.now() > deadline + HOLD_GRACE_MS ? "hold-expired" : null;
  }
  const bound = new Date(order.createdAt).getTime() + HOLD_MAX_MS;
  return Date.now() > bound + HOLD_GRACE_MS ? "no-progress" : null;
}

const POLL_STOPPED_COPY: Record<PollStopReason, string> = {
  "hold-expired":
    "The seat hold for this order has expired, so this page will no longer update on its own. " +
    "The order may still be resolving — refresh to check its latest status.",
  "no-progress":
    "This order has not progressed and its seat hold can no longer be live, so this page will no " +
    "longer update on its own. The order may still be resolving — refresh to check its latest status.",
};

const STATUS_VARIANT: Record<OrderStatus, string> = {
  CONFIRMED: "success",
  FAILED: "danger",
  CANCELLED: "danger",
  ACCEPTED: "warning",
  RESERVING: "warning",
  PAYMENT_PENDING: "warning",
  CONFIRMING_INVENTORY: "warning",
  COMPENSATING: "accent",
};


export function OrderStatusPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const [order, setOrder] = useState<OrderView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [route, setRoute] = useState<Route | null>(null);
  const [lastKnownStepIndex, setLastKnownStepIndex] = useState(0);
  const [stopReason, setStopReason] = useState<PollStopReason | null>(null);
  const [refreshNonce, setRefreshNonce] = useState(0);

  useEffect(() => {
    if (!orderId) return;
    let stop = false;
    const ac = new AbortController();
    setLastKnownStepIndex(0); // new orderId: this session hasn't observed anything about it yet
    setStopReason(null);

    async function poll() {
      while (!stop) {
        try {
          const o = await getOrder(orderId!, ac.signal);
          if (stop) return;
          setOrder(o);
          const idx = HAPPY_PATH.findIndex((s) => s.status === o.status);
          if (idx >= 0) setLastKnownStepIndex((prev) => Math.max(prev, idx));
          if (isTerminal(o.status) || o.status === "COMPENSATING") return;
          const reason = pollStopReason(o);
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
  }, [orderId, refreshNonce]);

  useEffect(() => {
    if (!order) return;
    const ac = new AbortController();
    getRoute(order.routeId, ac.signal)
      .then(setRoute)
      .catch(() => {}); // keep showing the raw routeId — see the comment on `route` above
    return () => ac.abort();
  }, [order?.routeId]);

  const countdown = useCountdown(
    order?.reservationExpiresAt ?? null,
    !!order && !isTerminal(order.status) && !stopReason,
  );

  if (error) return <p className="text-danger">{error}</p>;
  if (!order) return <p>Loading order…</p>;

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
              {countdown && (
                <div className="bg-primary text-white rounded-3 px-3 py-2 text-center">
                  <div className="text-uppercase" style={{ fontSize: "0.6rem", letterSpacing: "0.08em" }}>
                    Hold expires in
                  </div>
                  <div className="fw-bold fs-5 lh-1">{countdown}</div>
                </div>
              )}
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
                {order.totalAmount.toFixed(2)} {CURRENCY}
              </div>
            </div>
          </div>

          {!isTerminal(order.status) && order.status !== "COMPENSATING" && !stopReason && (
            <div className="text-secondary-emphasis d-flex align-items-center gap-2">
              <Spinner animation="border" size="sm" /> Refreshing…
            </div>
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
                onClick={() => {
                  setStopReason(null);
                  setRefreshNonce((n) => n + 1);
                }}
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
            <Alert variant="success" className="mb-0 fw-semibold">
              Your booking is confirmed.
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
        <Link to="/" className="btn btn-primary fw-semibold">
          <i className="bi bi-arrow-left me-2" aria-hidden="true" />
          Back to routes
        </Link>
      </div>
    </div>
  );
}
