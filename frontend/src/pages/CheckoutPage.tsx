import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { Card, Col, Form, Row } from "react-bootstrap";
import { placeOrder } from "../api/orders";
import { ApiError, newId } from "../api/client";
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
}

const SANDBOX_METHODS = [
  { token: "tok_visa", label: "Test card — successful payment" },
  { token: "tok_chargeDeclined", label: "Test card — declined payment" },
];

/**
 * Checkout form. Client-side validation is UX only (07b slide 26 — server is the truth).
 * A fresh Idempotency-Key is generated per attempt and reused only on retry of THIS attempt
 * (kept in state), so double-clicking never creates two orders.
 */
export function CheckoutPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const auth = useAuth();
  const state = location.state as CheckoutState | null;

  const [paymentToken, setPaymentToken] = useState(SANDBOX_METHODS[0]!.token);
  const [attemptKey] = useState(newId());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!state) navigate("/", { replace: true });
  }, [state, navigate]);

  if (!state) return null;
  const order: CheckoutState = state;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await placeOrder(
        {
          routeId: order.routeId,
          seats: order.seats,
          travelDate: order.travelDate,
          totalAmount: order.totalAmount,
          paymentMethodToken: paymentToken,
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
        <h1 className="fw-bold mb-1">Checkout</h1>
        <p className="text-uppercase text-secondary-emphasis small fw-semibold mb-0">
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
                  Sandbox environment: no real card is required, just pick a test scenario.
                </p>
                <div className="d-flex flex-column gap-2">
                  {SANDBOX_METHODS.map((m) => (
                    <Form.Check
                      key={m.token}
                      type="radio"
                      id={`payment-${m.token}`}
                      name="paymentMethod"
                      label={m.label}
                      checked={paymentToken === m.token}
                      onChange={() => setPaymentToken(m.token)}
                    />
                  ))}
                </div>
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
              actionDisabled={submitting}
              actionType="submit"
            />
          </Col>
        </Row>
      </Form>
    </>
  );
}
