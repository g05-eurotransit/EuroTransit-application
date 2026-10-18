import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { Alert, Badge, Button, Modal, Spinner } from "react-bootstrap";
import {
  cutoffLabel,
  getOrder,
  isTerminal,
  refundOrder,
  refundWindow,
  type OrderView,
} from "../api/orders";
import { newId } from "../api/client";
import { useIsOperator } from "../auth/roles";
import { getRoute, listOffers, type Route } from "../api/catalog";
import { ApiError } from "../api/client";
import { OrderStepper, stepIndexOf } from "../components/ticket/OrderStepper";
import { BackButton } from "../components/controls/BackButton";
import { TripBand } from "../components/ticket/TripBand";
import { TicketStub } from "../components/ticket/TicketStub";
import { HoldPill } from "../components/ticket/HoldClock";
import { statusLabel } from "../status/labels";
import { clockOffsetFrom } from "../hooks/useCountdown";
import { formatInstant, formatTravelDate } from "../utils/dates";
import { money, shortRef } from "../utils/format";

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
  // The class belongs to the offer, not to the order, so it takes one more read.
  const [fareClass, setFareClass] = useState<string | null>(null);
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

  useEffect(() => {
    if (!order?.offerId) return;
    const ac = new AbortController();
    listOffers(ac.signal)
      .then((offers) => {
        const offer = offers.find((o) => o.offerId === order.offerId);
        if (offer && !ac.signal.aborted) setFareClass(offer.fareClass);
      })
      .catch(() => {}); // the ticket reads correctly without it
    return () => ac.abort();
  }, [order?.offerId]);

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

  const label = statusLabel(order.status);
  const held = order.status === "HELD_AWAITING_PAYMENT";
  const pollStalled =
    !isTerminal(order.status) && order.status !== "COMPENSATING" && stopReason !== null;
  const footerNotices = held || pollStalled || order.status === "FAILED";
  // Where the arrow goes, named after where it lands.
  // "To pay" on a refunded or failed order would ask for money nobody owes.
  const amountLabel =
    order.status === "CONFIRMED"
      ? "Paid"
      : order.status === "REFUNDED"
        ? "Refunded"
        : order.status === "REFUNDING"
          ? "Refunding"
          : order.status === "FAILED" || order.status === "CANCELLED"
            ? "Order total"
            : "To pay";
  const back = isOperator
    ? { to: "/operations", label: "Back to reconciliation" }
    : fromTickets
      ? { to: "/orders", label: "Back to my tickets" }
      : { to: "/", label: "Back to departures" };

  return (
    <div className="mx-auto" style={{ maxWidth: 640 }}>

      <div className="d-flex align-items-center gap-3 mb-4">
        <BackButton to={back.to} label={back.label} />
        <h1 className="fw-bold mb-0">Order</h1>
      </div>

      {/* One surface: band, stepper, facts and stub are parts of one ticket. */}
      <div className="et-surface">
        <div className="d-flex flex-wrap justify-content-between align-items-center gap-2 px-3 py-2">
          <span className="et-mono small text-secondary-emphasis">#{shortRef(order.orderId)}</span>
          <span className="d-flex align-items-center gap-2">
            {held && (
              <HoldPill
                expiresAt={order.reservationExpiresAt ?? null}
                clockOffsetMs={clockOffsetFrom(order.serverNow)}
                active
              />
            )}
            <Badge bg={label.bg} text={label.text} className="fs-6">
              {label.label}
            </Badge>
          </span>
        </div>

        {route && (
          <TripBand
            origin={route.origin}
            destination={route.destination}
            departureTime={route.departureTime}
            arrivalTime={route.arrivalTime}
            travelTime={route.travelTime}
            service={fareClass ? `${route.trainType} · ${fareClass}` : route.trainType}
            dead={order.status === "FAILED" || order.status === "CANCELLED"}
          />
        )}

        <OrderStepper status={order.status} lastKnownStepIndex={lastKnownStepIndex} />

        {/* FAILED says why further down, in its own words. */}
        {label.sentence && order.status !== "FAILED" && (
          <p className="px-3 pt-3 mb-0 small text-secondary-emphasis">{label.sentence}</p>
        )}

        <div className="d-flex flex-wrap gap-4 px-3 py-3">
          <div>
            <span className="et-lab">Travel date</span>
            <div className="fw-bold">{formatTravelDate(order.travelDate)}</div>
          </div>
          <div>
            <span className="et-lab">Route</span>
            <div className="fw-bold">
              {route ? `${route.origin} → ${route.destination}` : order.routeId}
            </div>
          </div>
          <div>
            <span className="et-lab">{amountLabel}</span>
            <div className="fw-bold et-money fs-5">{money(order.totalAmount, order.currency)}</div>
          </div>
        </div>


        <TicketStub status={order.status} seats={order.seats} orderId={order.orderId} />

        {/* Only when it has something to say: an empty one ends the card with stray padding. */}
        {footerNotices && (
          <div className="px-3 pb-3 d-flex flex-column gap-3">
            {held && (
              <Alert variant="warning" className="d-flex flex-wrap align-items-center gap-3 mb-0">
                <div className="flex-grow-1">Your seats are held and waiting for your payment.</div>
                <Link
                  to={`/checkout?orderId=${encodeURIComponent(order.orderId)}`}
                  className="btn btn-primary btn-sm fw-semibold"
                >
                  Complete payment
                </Link>
              </Alert>
            )}
            {pollStalled && (
              <Alert variant="warning" className="mb-0">
                <div className="d-flex align-items-start gap-2 mb-2">
                  <i className="bi bi-clock-history fs-5" aria-hidden="true" />
                  <div>{POLL_STOPPED_COPY[stopReason!]}</div>
                </div>
                <Button variant="outline-dark" size="sm" disabled={rechecking} onClick={() => void recheck()}>
                  <i className="bi bi-arrow-clockwise me-2" aria-hidden="true" />
                  Refresh
                </Button>
              </Alert>
            )}
            {order.status === "FAILED" && (
              <Alert variant="danger" className="mb-0">
                {(order.failureCode && FAILURE_COPY[order.failureCode]) ?? GENERIC_FAILURE_COPY}
              </Alert>
            )}
          </div>
        )}
      </div>

      {/* Outside the card: an action *on* the ticket, not printed on its own paper. Quiet,
          because it is rare and irreversible — the red belongs to the dialog that follows. */}
      {order.status === "CONFIRMED" && (
        <div className="mt-3">
          {fromTickets && refundError && (
            <Alert variant="danger" role="alert">
              {refundError}
            </Alert>
          )}
          <div className="d-flex flex-wrap justify-content-between align-items-center gap-2 px-1">
            <span className="text-secondary-emphasis small">
              {refund.open
                ? `Refundable until ${cutoffLabel(order.refundCutoffMinutesBeforeDeparture)} — ${formatInstant(refund.deadline)}.`
                : refund.reason}
            </span>
            {fromTickets && refund.open && (
              <button type="button" className="et-refund" onClick={() => setConfirmingRefund(true)}>
                Refund this ticket
              </button>
            )}
          </div>
        </div>
      )}

      <Modal show={confirmingRefund} onHide={() => setConfirmingRefund(false)} centered>
        <Modal.Header closeButton>
          <Modal.Title className="fs-5">Refund this ticket?</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <p className="mb-0">
            {money(order.totalAmount, order.currency)} goes back to the original payment method.
            The seat returns to sale and the ticket is void.
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
