import { useState, useEffect, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { placeOrder } from "../api/orders";
import { ApiError, newId } from "../api/client";

interface CheckoutState {
  routeId?: string;
  price?: number;
  currency?: string;
}

/**
 * Checkout form. Client-side validation is UX only (07b slide 26 — server is the truth).
 * A fresh Idempotency-Key is generated per attempt and reused only on retry of THIS attempt
 * (kept in state), so double-clicking never creates two orders.
 */
export function CheckoutPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const state = (location.state as CheckoutState) ?? {};

  const [routeId, setRouteId] = useState(state.routeId ?? "");
  const [seats, setSeats] = useState("1A");
  const [amount, setAmount] = useState(state.price ?? 0);
  // Sandbox test token; format to confirm with the Payments slice.
  const [paymentToken] = useState("tok_visa");
  const [attemptKey, setAttemptKey] = useState(newId());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setAttemptKey(newId());
  }, [routeId, seats, amount]);

  const seatList = seats
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  const valid = routeId.length > 0 && seatList.length > 0 && amount > 0;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!valid || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await placeOrder(
        { routeId, seats: seatList, totalAmount: amount, paymentMethodToken: paymentToken },
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
      <h1>Checkout</h1>
      <form className="card" onSubmit={onSubmit}>
        <label htmlFor="routeId">Route</label>
        <input id="routeId" value={routeId} onChange={(e) => setRouteId(e.target.value)} />

        <label htmlFor="seats">Seats (comma-separated)</label>
        <input id="seats" value={seats} onChange={(e) => setSeats(e.target.value)} />

        <label htmlFor="amount">Total amount</label>
        <input
          id="amount"
          type="number"
          step="0.01"
          value={amount}
          onChange={(e) => setAmount(Number(e.target.value))}
        />

        <p className="muted">Payment: sandbox test token ({paymentToken})</p>

        {error && <p className="error">{error}</p>}
        <button className="btn" type="submit" disabled={!valid || submitting}>
          {submitting ? "Placing order…" : "Place order"}
        </button>
      </form>
    </>
  );
}
