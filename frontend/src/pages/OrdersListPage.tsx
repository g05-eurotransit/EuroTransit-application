import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Badge, Button, ButtonGroup, Card, Spinner, Stack, ToggleButton } from "react-bootstrap";
import { listOrders, type OrderStatus, type OrderView } from "../api/orders";
import { ApiError } from "../api/client";

const CURRENCY = "EUR";

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

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

/** The two reachable views: "My tickets" (confirmed only) and the unfiltered list. */
type ViewFilter = "CONFIRMED" | "ALL";

export function OrdersListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filter: ViewFilter = searchParams.get("status") === "ALL" ? "ALL" : "CONFIRMED";

  const [orders, setOrders] = useState<OrderView[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const ac = new AbortController();
    setLoading(true);
    setError(null);
    setOrders([]);
    setCursor(null);
    listOrders({ status: filter === "CONFIRMED" ? "CONFIRMED" : undefined }, ac.signal)
      .then((res) => {
        setOrders(res.orders);
        setCursor(res.nextCursor);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.kind === "network") return; // aborted
        setError(e instanceof Error ? e.message : "Failed to load orders");
      })
      .finally(() => setLoading(false));
    return () => ac.abort();
  }, [filter]);

  function loadMore() {
    if (!cursor) return;
    setLoadingMore(true);
    listOrders({ status: filter === "CONFIRMED" ? "CONFIRMED" : undefined, cursor })
      .then((res) => {
        setOrders((prev) => [...prev, ...res.orders]);
        setCursor(res.nextCursor);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load more orders"))
      .finally(() => setLoadingMore(false));
  }

  return (
    <div className="mx-auto" style={{ maxWidth: 720 }}>
      <div className="d-flex flex-wrap justify-content-between align-items-end mb-4 pb-3 border-bottom">
        <h1 className="fw-bold mb-0">{filter === "CONFIRMED" ? "My tickets" : "All orders"}</h1>
        <ButtonGroup>
          <ToggleButton
            id="orders-filter-confirmed"
            name="orders-filter"
            type="radio"
            variant="outline-primary"
            checked={filter === "CONFIRMED"}
            value="CONFIRMED"
            onChange={() => setSearchParams({ status: "CONFIRMED" })}
          >
            My tickets
          </ToggleButton>
          <ToggleButton
            id="orders-filter-all"
            name="orders-filter"
            type="radio"
            variant="outline-primary"
            checked={filter === "ALL"}
            value="ALL"
            onChange={() => setSearchParams({ status: "ALL" })}
          >
            All orders
          </ToggleButton>
        </ButtonGroup>
      </div>

      {error && <p className="text-danger">{error}</p>}
      {loading && (
        <div className="text-secondary-emphasis d-flex align-items-center gap-2">
          <Spinner animation="border" size="sm" /> Loading…
        </div>
      )}

      {!loading && !error && orders.length === 0 && (
        <p className="text-secondary-emphasis text-center py-4">
          {filter === "CONFIRMED" ? "No confirmed tickets yet." : "No orders yet."}
        </p>
      )}

      <Stack gap={3}>
        {orders.map((o) => (
          <Card
            as={Link}
            to={`/orders/${o.orderId}`}
            // The order page shows the refund button only for an arrival from this list.
            state={{ fromTickets: true }}
            key={o.orderId}
            className="shadow-sm border-0 text-decoration-none"
          >
            <Card.Body className="d-flex justify-content-between align-items-center gap-3">
              <div>
                <div className="text-secondary-emphasis small mb-1">Order #{o.orderId.slice(0, 8)}</div>
                <div className="fs-5 fw-bold text-dark">{formatDate(o.travelDate)}</div>
                <div className="text-secondary-emphasis small">Seats {o.seats.join(", ")}</div>
              </div>
              <div className="d-flex flex-column align-items-end gap-2">
                <Badge bg={STATUS_VARIANT[o.status]} className="fs-6">
                  {o.status}
                </Badge>
                <div className="fw-bold text-dark">
                  {o.totalAmount.toFixed(2)} {CURRENCY}
                </div>
              </div>
            </Card.Body>
          </Card>
        ))}
      </Stack>

      {cursor && (
        <div className="text-center mt-3">
          <Button variant="outline-primary" disabled={loadingMore} onClick={loadMore}>
            {loadingMore ? <Spinner animation="border" size="sm" /> : "Load more"}
          </Button>
        </div>
      )}
    </div>
  );
}
