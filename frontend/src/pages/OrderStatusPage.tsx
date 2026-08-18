import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { getOrder, isTerminal, type OrderView } from "../api/orders";
import { ApiError } from "../api/client";

/**
 * Order status view with polling until a terminal state (clear success/failure states,
 * assignment requirement). Polling stops on CONFIRMED/FAILED/CANCELLED.
 */
export function OrderStatusPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const [order, setOrder] = useState<OrderView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!orderId) return;
    let stop = false;
    const ac = new AbortController();

    async function poll() {
      while (!stop) {
        try {
          const o = await getOrder(orderId!, ac.signal);
          if (stop) return;
          setOrder(o);
          if (isTerminal(o.status)) return;
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
  }, [orderId]);

  if (error) return <p className="error">{error}</p>;
  if (!order) return <p>Loading order…</p>;

  return (
    <>
      <h1>Order</h1>
      <div className="card">
        <div>
          Status: <span className={`status ${order.status}`}>{order.status}</span>
        </div>
        <div className="muted">Order {order.orderId}</div>
        <div>Route: {order.routeId}</div>
        <div>Seats: {order.seats.join(", ")}</div>
        <div>
          Total: {order.totalAmount.toFixed(2)}
        </div>
        {!isTerminal(order.status) && <p className="muted">Refreshing…</p>}
        {order.status === "CONFIRMED" && <p>Your booking is confirmed. 🎉</p>}
        {(order.status === "FAILED" || order.status === "CANCELLED") && (
          <p className="error">This order did not complete.</p>
        )}
      </div>
    </>
  );
}
