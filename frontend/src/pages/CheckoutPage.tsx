import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { Card, Col, Form, Row } from "react-bootstrap";
import { loadStripe } from "@stripe/stripe-js";
import {
  CardCvcElement,
  CardExpiryElement,
  CardNumberElement,
  Elements,
  useElements,
  useStripe,
} from "@stripe/react-stripe-js";
import { placeOrder } from "../api/orders";
import { ApiError, newId } from "../api/client";
import { getRuntimeConfig } from "../config/runtimeConfig";
import { BackButton } from "../components/BackButton";
import { OrderSummaryCard } from "../components/OrderSummaryCard";

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
}

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

export function CheckoutPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const state = location.state as CheckoutState | null;

  useEffect(() => {
    if (!state) navigate("/", { replace: true });
  }, [state, navigate]);

  if (!state) return null;

  return (
    <Elements stripe={getStripePromise()}>
      <CheckoutForm order={state} />
    </Elements>
  );
}

function CheckoutForm({ order }: { order: CheckoutState }) {
  const navigate = useNavigate();
  const auth = useAuth();
  const stripe = useStripe();
  const elements = useElements();

  const [attemptKey] = useState(newId());
  const [submitting, setSubmitting] = useState(false);
  const [completeFields, setCompleteFields] = useState<Record<CardFieldName, boolean>>({
    number: false,
    expiry: false,
    cvc: false,
  });
  const [error, setError] = useState<string | null>(null);
  const cardComplete = completeFields.number && completeFields.expiry && completeFields.cvc;

  function fieldComplete(field: CardFieldName) {
    return (e: { complete: boolean }) =>
      setCompleteFields((prev) => ({ ...prev, [field]: e.complete }));
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (submitting || !stripe || !elements) return;
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

      const res = await placeOrder(
        {
          routeId: order.routeId,
          seats: order.seats,
          travelDate: order.travelDate,
          totalAmount: order.totalAmount,
          paymentMethodToken: token.id,
          offerId: order.offerId,
        },
        attemptKey, // reused if the user retries this same attempt
        newId(), // fresh correlation id per action
      );
      navigate(`/orders/${res.orderId}`);
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.kind === "conflict") {
          setError("This checkout was already submitted. Check your orders.");
        } else if (e.kind === "business") {
          setError(`Checkout rejected: ${e.message}`);
        } else if (e.kind === "dependency") {
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
      <div className="mb-4 pb-3 border-bottom">
        <div className="d-flex align-items-center gap-3">
          <BackButton />
          <h1 className="fw-bold mb-1">Checkout</h1>
        </div>
        <p
          className="text-uppercase text-secondary-emphasis small fw-semibold mb-0"
          style={{ marginLeft: "3.5rem" }}
        >
          Complete the details to confirm your trip.
        </p>
      </div>

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

            {error && <p className="text-danger">{error}</p>}
          </Col>

          <Col lg={5}>
            <OrderSummaryCard
              origin={order.origin}
              destination={order.destination}
              departureTime={order.departureTime}
              arrivalTime={order.arrivalTime}
              trainType={order.trainType}
              fareClass={order.fareClass}
              travelDate={order.travelDate}
              seats={order.seats}
              totalAmount={order.totalAmount}
              currency={order.currency}
              actionLabel={submitting ? "Placing order…" : "Pay now"}
              actionDisabled={submitting || !stripe || !cardComplete}
              actionType="submit"
            />
          </Col>
        </Row>
      </Form>
    </>
  );
}
