import { useEffect, useRef, useState, type FormEvent } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { Button, Card, Col, Form, Row, Spinner } from "react-bootstrap";
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

const SEATS_TAKEN_NOTICE = "Sorry, those seats were just taken. Please choose others.";
const TIME_RAN_OUT_NOTICE = "The time to pay for your seats ran out, so they were released. Please choose again.";
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
  const [attemptKey] = useState(newId());
  const [submitting, setSubmitting] = useState(false);
  const [completeFields, setCompleteFields] = useState<Record<CardFieldName, boolean>>({
    number: false,
    expiry: false,
    cvc: false,
  });
  const [error, setError] = useState<string | null>(null);
  const leaving = useRef(false);
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

  // Waits for the hold the seat page asked for, then reads the order once: the countdown runs locally.
  useEffect(() => {
    const ac = new AbortController();
    let stop = false;

    async function watch() {
      while (!stop) {
        try {
          const o = await getOrder(orderId, ac.signal);
          if (stop) return;
          setClockOffsetMs(clockOffsetFrom(o.serverNow));
          setOrder(o);
          if (o.status === "HELD_AWAITING_PAYMENT") return;
          if (o.status === "FAILED") return leave(SEATS_TAKEN_NOTICE);
          if (o.status === "CANCELLED") return leave(NO_LONGER_ACTIVE_NOTICE);
          if (o.status !== "ACCEPTED" && o.status !== "RESERVING") {
            // The payment already started, from here or from another tab: follow it there.
            leaving.current = true;
            return navigate(`/orders/${encodeURIComponent(orderId)}`, { replace: true });
          }
        } catch (e) {
          if (e instanceof ApiError && e.kind === "network") return; // aborted
          setError(
            e instanceof ApiError && e.kind === "not_found"
              ? "This checkout could not be found."
              : "We could not load your checkout. Please refresh.",
          );
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

  useEffect(() => {
    if (summary || !order) return;
    const ac = new AbortController();
    summaryFromOrder(order, ac.signal).then(setSummary).catch(() => {});
    return () => ac.abort();
  }, [order, summary]);

  // Time is up: release the seats now (best effort — the server's expiry job would do it within
  // seconds) so the customer can hold again straight away, and go back to the seat map.
  useEffect(() => {
    if (!held || !countdown.expired || submitting) return;
    void cancelOrder(orderId).catch(() => {});
    leave(TIME_RAN_OUT_NOTICE);
  }, [held, countdown.expired, submitting]);

  async function goBack() {
    // An explicit "back" is the customer giving the seats up (ADR-016). Leaving the page any other
    // way does not cancel: the hold simply runs out, and the seat page offers to resume it.
    try {
      await cancelOrder(orderId);
    } catch {
      // Already paid or already gone: nothing to release from here.
    }
    leave();
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
      const { token, error: tokenError } = await stripe.createToken(cardNumberElement);
      if (tokenError || !token) {
        setError(tokenError?.message ?? "Could not process the card.");
        return;
      }

      await submitPayment(
        orderId,
        token.id,
        attemptKey, // reused if the user retries this same attempt
        newId(), // fresh correlation id per action
      );
      leaving.current = true;
      navigate(`/orders/${encodeURIComponent(orderId)}`);
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === "HOLD_EXPIRED" || e.code === "ORDER_NOT_PAYABLE") {
          leave(TIME_RAN_OUT_NOTICE);
        } else if (e.code === "PAYMENT_ALREADY_STARTED") {
          leaving.current = true;
          navigate(`/orders/${encodeURIComponent(orderId)}`);
        } else if (e.kind === "business") {
          setError(`Checkout rejected: ${e.message}`);
        } else if (e.kind === "dependency" || e.kind === "network") {
          setError("A downstream service is temporarily unavailable. Try again shortly.");
        } else {
          setError(e.message);
        }
      } else {
        setError("Unexpected error");
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
              disabled={submitting}
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
    </>
  );
}
