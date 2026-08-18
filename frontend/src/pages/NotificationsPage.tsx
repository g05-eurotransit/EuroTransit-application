import { useEffect, useState } from "react";
import {
  listNotifications,
  subscribeNotifications,
  type NotificationView,
} from "../api/notifications";

/**
 * Notifications: initial history + live SSE feed. Degrades gracefully (assignment): if the
 * feed or history is unavailable, the page shows a soft "unavailable" note and never blocks
 * anything — Notifications is a best-effort, optional feature.
 */
export function NotificationsPage() {
  const [items, setItems] = useState<NotificationView[]>([]);
  const [live, setLive] = useState(false);
  const [degraded, setDegraded] = useState(false);

  useEffect(() => {
    const ac = new AbortController();

    listNotifications(ac.signal)
      .then(setItems)
      .catch(() => setDegraded(true)); // history unavailable -> soft-fail, keep the page usable

    const unsubscribe = subscribeNotifications({
      onOpen: () => {
        setLive(true);
        setDegraded(false);
      },
      onNotification: (n) =>
        setItems((prev) => (prev.some((p) => p.id === n.id) ? prev : [n, ...prev])),
      onError: () => {
        setLive(false);
        setDegraded(true); // stream down -> we already have history; poll fallback could go here
      },
    });

    return () => {
      ac.abort();
      unsubscribe();
    };
  }, []);

  return (
    <>
      <h1>
        Notifications{" "}
        <span className="muted" style={{ fontSize: "0.9rem" }}>
          {live ? "· live" : degraded ? "· offline" : ""}
        </span>
      </h1>
      {degraded && (
        <p className="muted">
          Live updates are temporarily unavailable. This does not affect your orders.
        </p>
      )}
      {items.length === 0 && !degraded && <p className="muted">No notifications yet.</p>}
      {items.map((n) => (
        <div className="card" key={n.id}>
          <div>{n.message}</div>
          <div className="muted">
            Order {n.orderId} · {n.occurredAt.replace("T", " ").slice(0, 19)}
          </div>
        </div>
      ))}
    </>
  );
}
