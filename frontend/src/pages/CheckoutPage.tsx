import { useEffect, useRef, useState, type FormEvent } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { Button, Card, Col, Form, Modal, Row, Spinner } from "react-bootstrap";
import { loadStripe } from "@stripe/stripe-js";
import {
  CardCvcElement,
  CardExpiryElement,
  CardNumberElement,
  Elements,
  useElements,
  useStripe,
} from "@stripe/react-stripe-js";
import { cancelOrder, getOrder, submitPayment, type OrderView } from "../api/orders";
import { getRoute, listOffers } from "../api/catalog";
import { ApiError, newId } from "../api/client";
import { getRuntimeConfig } from "../config/runtimeConfig";
import { OrderSummaryCard } from "../components/OrderSummaryCard";
import { clockOffsetFrom, useCountdown } from "../hooks/useCountdown";

/** What the seat page hands over: the offer it was showing, the chosen seats and the held order. */
interface CheckoutState {
  routeId: string;
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
  offerId: string;
  price?: number;
  conditions?: string;
  orderId?: string;
}

interface Summary {
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
}

const HOLD_POLL_MS = 1000;
// The hold normally takes a second or two. Past this the page stops waiting and says so, instead of
// spinning for as long as the tab stays open.
const HOLD_WAIT_LIMIT_MS = 60_000;

const SEATS_TAKEN_NOTICE = "Sorry, those seats were just taken. Please choose others.";
const HOLD_FAILED_NOTICE = "We could not hold those seats. Please try again.";
// How long the "time's up" message stays on screen before the page goes back to the seats by itself.
const TIME_UP_REDIRECT_SECONDS = 5;
const NO_LONGER_ACTIVE_NOTICE = "That checkout is no longer active, so its seats were released. Please choose again.";

let stripePromise: ReturnType<typeof loadStripe> | undefined;
function getStripePromise() {
  if (!stripePromise) {
    stripePromise = loadStripe(getRuntimeConfig().stripePublishableKey);
  }
  return stripePromise;
}

const CARD_ELEMENT_OPTIONS = {
  style: {
    base: {
      fontSize: "16px",
      fontFamily: "Montserrat, sans-serif",
      color: "#212529",
      "::placeholder": { color: "#6c757d" },
    },
    invalid: { color: "#dc3545" },
  },
};

type CardFieldName = "number" | "expiry" | "cvc";

/** States in which the order still belongs on this page; anything else has gone past the payment. */
const BEFORE_PAYMENT = new Set<OrderView["status"]>(["ACCEPTED", "RESERVING", "HELD_AWAITING_PAYMENT", "CANCELLED"]);

/**
 * One payment attempt: its Idempotency-Key and the Stripe token it sent. Kept together because Orders
 * fingerprints the token — a retry after an unknown outcome must resend BOTH, so it replays the
 * first answer instead of being refused as a different request.
 */
interface PaymentAttempt {
  key: string;
  tokenId: string;
}

/**
 * The payment step of the customer-hold checkout (ADR-016). The seats are already held for the
 * order in `?orderId=`: this page waits for the hold, counts down to its deadline (T1) and submits the
 * card. The order id lives in the URL, so a reload resumes the same order instead of holding again.
 */
export function CheckoutPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const orderId = params.get("orderId");
  const state = location.state as CheckoutState | null;

  useEffect(() => {
    if (!orderId) navigate("/", { replace: true });
  }, [orderId, navigate]);

  if (!orderId) return null;

  return (
    <Elements stripe={getStripePromise()}>
      <CheckoutForm orderId={orderId} offer={state} />
    </Elements>
  );
}

function summaryFromState(offer: CheckoutState): Summary {
  return {
    origin: offer.origin,
    destination: offer.destination,
    departureTime: offer.departureTime,
    arrivalTime: offer.arrivalTime,
    trainType: offer.trainType,
    fareClass: offer.fareClass,
    travelDate: offer.travelDate,
    seats: offer.seats,
    totalAmount: offer.totalAmount,
    currency: offer.currency,
  };
}

/** Rebuilds the summary for a resumed order that arrived without the seat page's state. */
async function summaryFromOrder(order: OrderView, signal: AbortSignal): Promise<Summary> {
  const [route, offers] = await Promise.all([
    getRoute(order.routeId, signal).catch(() => null),
    listOffers(signal).catch(() => []),
  ]);
  const offer = offers.find((o) => o.offerId === order.offerId);
  return {
    origin: route?.origin ?? order.routeId,
    destination: route?.destination ?? "",
    departureTime: route?.departureTime ?? "",
    arrivalTime: route?.arrivalTime ?? "",
    trainType: route?.trainType ?? "",
    fareClass: offer?.fareClass ?? "",
    travelDate: order.travelDate,
    seats: order.seats,
    totalAmount: order.totalAmount,
    currency: offer?.currency ?? "EUR",
  };
}

function CheckoutForm({ orderId, offer }: { orderId: string; offer: CheckoutState | null }) {
  const navigate = useNavigate();
  const auth = useAuth();
  const stripe = useStripe();
  const elements = useElements();

  const [order, setOrder] = useState<OrderView | null>(null);
  const [clockOffsetMs, setClockOffsetMs] = useState(0);
  const [summary, setSummary] = useState<Summary | null>(offer ? summaryFromState(offer) : null);
  const pendingAttempt = useRef<PaymentAttempt | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [completeFields, setCompleteFields] = useState<Record<CardFieldName, boolean>>({
    number: false,
    expiry: false,
    cvc: false,
  });
  const [error, setError] = useState<string | null>(null);
  const [reconnecting, setReconnecting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  // Seconds left before going back, while the "time's up" message is on screen; null when it is not.
  const [timeUpSeconds, setTimeUpSeconds] = useState<number | null>(null);
  const leaving = useRef(false);
  const cancelInFlight = useRef(false);
  const cardComplete = completeFields.number && completeFields.expiry && completeFields.cvc;
  const held = order?.status === "HELD_AWAITING_PAYMENT";
  const countdown = useCountdown(order?.reservationExpiresAt ?? null, held, clockOffsetMs);

  /**
   * Back to the seat map for the same offer, with a message when there is one. A resumed order came
   * without the offer, so it goes back to the start instead.
   */
  function leave(notice?: string) {
    if (leaving.current) return;
    leaving.current = true;
    if (offer?.price !== undefined) {
      navigate("/checkout/seats", {
        replace: true,
        state: {
          routeId: offer.routeId,
          origin: offer.origin,
          destination: offer.destination,
          departureTime: offer.departureTime,
          arrivalTime: offer.arrivalTime,
          trainType: offer.trainType,
          offerId: offer.offerId,
          fareClass: offer.fareClass,
          price: offer.price,
          currency: offer.currency,
          conditions: offer.conditions ?? "",
          travelDate: offer.travelDate,
          notice,
        },
      });
    } else {
      navigate("/", { replace: true });
    }
  }

  /** Follows the order to its own page: the payment has started, from here or from another tab. */
  function goToOrder() {
    if (leaving.current) return;
    leaving.current = true;
    navigate(`/orders/${encodeURIComponent(orderId)}`, { replace: true });
  }

  /**
   * Gives the seats up and goes back — unless the payment has already started, in which case the
   * customer is taken to the order instead of being told the seats were released.
   *
   * A refused cancel is not proof of a payment: 409 ORDER_NOT_CANCELLABLE also answers an order the
   * expiry job already cancelled, or a second click on Back. So any failure re-reads the order, and only
   * a status past the payment leads to the order page. One cancel at a time: Back and a countdown
   * reaching zero together must not fire two.
   */
  async function cancelThen(onReleased: () => void) {
    if (cancelInFlight.current || leaving.current) return;
    cancelInFlight.current = true;
    setCancelling(true);
    try {
      await cancelOrder(orderId);
      onReleased();
    } catch {
      try {
        const o = await getOrder(orderId);
        if (!BEFORE_PAYMENT.has(o.status)) return goToOrder();
      } catch {
        // Cannot tell: the order is unpaid as far as this page knows, and the hold runs out by itself.
      }
      onReleased();
    } finally {
      cancelInFlight.current = false;
      setCancelling(false);
    }
  }

  /** After an unknown payment outcome: follow the order if the payment got through, else let them retry. */
  async function reconcileAfterUnknownPayment(message: string) {
    try {
      const o = await getOrder(orderId);
      if (!BEFORE_PAYMENT.has(o.status)) return goToOrder();
      if (o.status === "CANCELLED") return leave(NO_LONGER_ACTIVE_NOTICE);
    } catch {
      // Still unknown: the retry below resends the same attempt, so it cannot pay twice.
    }
    setError(message);
  }

  // Waits for the hold the seat page asked for, then reads the order once: the countdown runs locally.
  useEffect(() => {
    const ac = new AbortController();
    let stop = false;
    const waitingSince = Date.now();

    async function watch() {
      while (!stop) {
        try {
          const o = await getOrder(orderId, ac.signal);
          if (stop) return;
          setClockOffsetMs(clockOffsetFrom(o.serverNow));
          setOrder(o);
          setReconnecting(false);
          if (o.status === "HELD_AWAITING_PAYMENT") return;
          if (o.status === "FAILED") {
            return leave(o.failureCode === "SEATS_UNAVAILABLE" ? SEATS_TAKEN_NOTICE : HOLD_FAILED_NOTICE);
          }
          if (o.status === "CANCELLED") return leave(NO_LONGER_ACTIVE_NOTICE);
          if (!BEFORE_PAYMENT.has(o.status)) return goToOrder();
        } catch (e) {
          // An abort (the page went away) also surfaces as a "network" error: only that one stops here.
          if (stop || ac.signal.aborted) return;
          if (e instanceof ApiError && e.kind === "not_found") {
            setError("This checkout could not be found.");
            return;
          }
          if (e instanceof ApiError && (e.kind === "unauthenticated" || e.kind === "forbidden")) {
            setError("Your session has expired. Please sign in again.");
            return;
          }
          // A real network blip or a 5xx: say so, and keep trying.
          setReconnecting(true);
        }
        if (Date.now() - waitingSince > HOLD_WAIT_LIMIT_MS) {
          setReconnecting(false);
          setError("Holding your seats is taking longer than usual. Refresh the page to check again.");
          return;
        }
        await new Promise((r) => setTimeout(r, HOLD_POLL_MS));
      }
    }
    void watch();
    return () => {
      stop = true;
      ac.abort();
    };
  }, [orderId]);

  // Keyed on the order id, not on the order object: every poll produces a new object, and re-running
  // this on each one would abort the catalog calls and could keep their fallback values.
  useEffect(() => {
    if (summary || !order) return;
    const ac = new AbortController();
    summaryFromOrder(order, ac.signal)
      .then((s) => {
        if (!ac.signal.aborted) setSummary(s);
      })
      .catch(() => {});
    return () => ac.abort();
  }, [order?.orderId, summary]);

  // Time is up: release the seats now (best effort — the server's expiry job would do it within
  // seconds) so the customer can hold again straight away, and go back to the seat map.
  useEffect(() => {
    if (!held || !countdown.expired || submitting || leaving.current || timeUpSeconds !== null) return;
    void cancelThen(showTimeUp);
  }, [held, countdown.expired, submitting]);

  /**
   * Time is up and the seats are released: say so in the middle of the screen, then go back to the
   * seat map by itself after a few seconds (or at once, from the button). Never shown when the payment
   * turned out to have started — [cancelThen] goes to the order instead.
   */
  function showTimeUp() {
    setTimeUpSeconds(TIME_UP_REDIRECT_SECONDS);
  }

  useEffect(() => {
    if (timeUpSeconds === null) return;
    if (timeUpSeconds <= 0) {
      leave();
      return;
    }
    const id = setTimeout(() => setTimeUpSeconds((s) => (s === null ? null : s - 1)), 1000);
    return () => clearTimeout(id);
  }, [timeUpSeconds]);

  function goBack() {
    // An explicit "back" is the customer giving the seats up (ADR-016). Leaving the page any other
    // way does not cancel: the hold simply runs out, and the seat page offers to resume it.
    void cancelThen(() => leave());
  }

  function fieldComplete(field: CardFieldName) {
    return (e: { complete: boolean }) =>
      setCompleteFields((prev) => ({ ...prev, [field]: e.complete }));
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (submitting || !stripe || !elements || !held) return;
    // Stripe.js only needs one split element's handle — it finds the sibling
    // expiry/CVC elements itself since they share this same <Elements> instance.
    const cardNumberElement = elements.getElement(CardNumberElement);
    if (!cardNumberElement) return;

    setSubmitting(true);
    setError(null);

    try {
      // A retry after an unknown outcome resends the same attempt; only a fresh attempt asks Stripe
      // for a new token.
      let attempt = pendingAttempt.current;
      if (!attempt) {
        const { token, error: tokenError } = await stripe.createToken(cardNumberElement);
        if (tokenError || !token) {
          setError(tokenError?.message ?? "Could not process the card.");
          return;
        }
        attempt = { key: newId(), tokenId: token.id };
        pendingAttempt.current = attempt;
      }

      await submitPayment(orderId, attempt.tokenId, attempt.key, newId());
      pendingAttempt.current = null;
      leaving.current = true;
      navigate(`/orders/${encodeURIComponent(orderId)}`);
    } catch (e) {
      if (!(e instanceof ApiError)) {
        setError("Unexpected error");
      } else if (e.code === "HOLD_EXPIRED") {
        // The same moment as the countdown reaching zero, seen from the server: release and say so.
        pendingAttempt.current = null;
        await cancelThen(showTimeUp);
      } else if (e.code === "ORDER_NOT_PAYABLE") {
        pendingAttempt.current = null;
        leave(NO_LONGER_ACTIVE_NOTICE);
      } else if (e.code === "PAYMENT_ALREADY_STARTED") {
        pendingAttempt.current = null;
        goToOrder();
      } else if (e.code === "IDEMPOTENCY_CONFLICT") {
        // Should not happen now that a retry resends the same token; if it does, the order knows.
        pendingAttempt.current = null;
        await reconcileAfterUnknownPayment("Your payment could not be confirmed. Please try again.");
      } else if (e.kind === "network" || e.kind === "dependency" || (e.kind === "unknown" && e.status >= 500)) {
        // Unknown outcome: the payment may have been accepted. Keep the attempt for the retry.
        await reconcileAfterUnknownPayment(
          "We could not confirm your payment. Try again — you will not be charged twice.",
        );
      } else {
        pendingAttempt.current = null;
        setError(e.kind === "business" ? `Checkout rejected: ${e.message}` : e.message);
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <div className="mb-4 pb-3 border-bottom d-flex flex-wrap justify-content-between align-items-center gap-3">
        <div>
          <div className="d-flex align-items-center gap-3">
            <Button
              variant="outline-primary"
              className="rounded-circle p-0 d-flex align-items-center justify-content-center flex-shrink-0"
              style={{ width: 40, height: 40 }}
              aria-label="Go back"
              disabled={submitting || cancelling}
              onClick={() => void goBack()}
            >
              <i className="bi bi-arrow-left" aria-hidden="true" />
            </Button>
            <h1 className="fw-bold mb-1">Checkout</h1>
          </div>
          <p
            className="text-uppercase text-secondary-emphasis small fw-semibold mb-0"
            style={{ marginLeft: "3.5rem" }}
          >
            Complete the details to confirm your trip.
          </p>
        </div>
        {held && countdown.label && (
          <div className="bg-primary text-white rounded-3 px-3 py-2 text-center" role="timer" aria-live="off">
            <div className="text-uppercase" style={{ fontSize: "0.6rem", letterSpacing: "0.08em" }}>
              Seats held for
            </div>
            <div className="fw-bold fs-5 lh-1">{countdown.label}</div>
          </div>
        )}
      </div>

      {error && <p className="text-danger">{error}</p>}

      {!held && !error && (
        <div className="text-secondary-emphasis d-flex align-items-center gap-2">
          <Spinner animation="border" size="sm" /> Holding your seats…
          {reconnecting && <span className="small">(connection problem — retrying)</span>}
        </div>
      )}

      {held && (
        <Form onSubmit={onSubmit}>
          <Row className="g-4">
            <Col lg={7} className="d-flex flex-column gap-4">
              <Card className="border-0 shadow-sm">
                <Card.Body>
                  <div className="d-flex align-items-center gap-3 mb-3">
                    <div
                      className="bg-light text-primary rounded-circle d-flex align-items-center justify-content-center"
                      style={{ width: 40, height: 40 }}
                    >
                      <i className="bi bi-person-fill" aria-hidden="true" />
                    </div>
                    <h2 className="fw-bold fs-4 mb-0">Booking</h2>
                  </div>
                  <p className="text-secondary-emphasis mb-0">
                    Booking as <strong>{auth.user?.profile.email ?? auth.user?.profile.preferred_username}</strong>.
                    Your tickets will be sent to this address.
                  </p>
                </Card.Body>
              </Card>

              <Card className="border-0 shadow-sm">
                <Card.Body>
                  <div className="d-flex align-items-center gap-3 mb-3">
                    <div
                      className="bg-light text-primary rounded-circle d-flex align-items-center justify-content-center"
                      style={{ width: 40, height: 40 }}
                    >
                      <i className="bi bi-credit-card-fill" aria-hidden="true" />
                    </div>
                    <h2 className="fw-bold fs-4 mb-0">Payment method</h2>
                  </div>
                  <p className="text-secondary-emphasis small">
                    Sandbox environment (Stripe test mode) — use{" "}
                    <code>4242 4242 4242 4242</code> for a successful payment, or{" "}
                    <code>4000 0000 0000 0002</code> for a decline. Any future expiry date and any
                    3-digit CVC work.
                  </p>
                  <Form.Group className="mb-3">
                    <Form.Label className="small text-secondary-emphasis fw-semibold">
                      Card number
                    </Form.Label>
                    <div className="form-control py-2" style={{ height: "auto" }}>
                      <CardNumberElement options={CARD_ELEMENT_OPTIONS} onChange={fieldComplete("number")} />
                    </div>
                  </Form.Group>
                  <Row className="g-3">
                    <Col xs={6}>
                      <Form.Group>
                        <Form.Label className="small text-secondary-emphasis fw-semibold">
                          Expiry date
                        </Form.Label>
                        <div className="form-control py-2" style={{ height: "auto" }}>
                          <CardExpiryElement options={CARD_ELEMENT_OPTIONS} onChange={fieldComplete("expiry")} />
                        </div>
                      </Form.Group>
                    </Col>
                    <Col xs={6}>
                      <Form.Group>
                        <Form.Label className="small text-secondary-emphasis fw-semibold">CVC</Form.Label>
                        <div className="form-control py-2" style={{ height: "auto" }}>
                          <CardCvcElement options={CARD_ELEMENT_OPTIONS} onChange={fieldComplete("cvc")} />
                        </div>
                      </Form.Group>
                    </Col>
                  </Row>
                </Card.Body>
              </Card>
            </Col>

            <Col lg={5}>
              {summary && (
                <OrderSummaryCard
                  origin={summary.origin}
                  destination={summary.destination}
                  departureTime={summary.departureTime}
                  arrivalTime={summary.arrivalTime}
                  trainType={summary.trainType}
                  fareClass={summary.fareClass}
                  travelDate={summary.travelDate}
                  seats={summary.seats}
                  totalAmount={summary.totalAmount}
                  currency={summary.currency}
                  actionLabel={submitting ? "Placing order…" : "Pay now"}
                  actionDisabled={submitting || !stripe || !cardComplete}
                  actionType="submit"
                />
              )}
            </Col>
          </Row>
        </Form>
      )}

      <Modal show={timeUpSeconds !== null} centered backdrop="static" keyboard={false} aria-labelledby="time-up-title">
        <Modal.Body className="text-center p-4">
          <i className="bi bi-clock-history text-primary" style={{ fontSize: "2.5rem" }} aria-hidden="true" />
          <h2 id="time-up-title" className="fw-bold fs-4 mt-2">Time's up</h2>
          <p className="text-secondary-emphasis mb-4">
            Your seats have been released. You'll be taken back to seat selection in{" "}
            {timeUpSeconds ?? 0} {timeUpSeconds === 1 ? "second" : "seconds"}.
          </p>
          <Button variant="primary" className="fw-semibold" onClick={() => leave()}>
            Choose seats now
          </Button>
        </Modal.Body>
      </Modal>
    </>
  );
}
