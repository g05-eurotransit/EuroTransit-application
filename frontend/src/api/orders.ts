import { apiFetch } from "./client";

export type OrderStatus =
  | "ACCEPTED"
  | "RESERVING"
  | "PAYMENT_PENDING"
  | "CONFIRMING_INVENTORY"
  | "CONFIRMED"
  | "FAILED"
  | "CANCELLED"
  | "COMPENSATING";

export interface CheckoutRequest {
  routeId: string;
  seats: string[];
  travelDate: string;
  totalAmount: number;
  paymentMethodToken: string;
  offerId?: string;
}

export interface CheckoutResponse {
  orderId: string;
  status: OrderStatus;
}

export type FailureCode = "SEATS_UNAVAILABLE" | "PAYMENT_DECLINED" | "CHECKOUT_FAILED";

export interface OrderView {
  orderId: string;
  userId: string;
  status: OrderStatus;
  routeId: string;
  seats: string[];
  travelDate: string;
  totalAmount: number;
  createdAt: string;
  reservationExpiresAt?: string | null;
  failureCode?: FailureCode | null;
  failureMessage?: string | null;
}

export interface ListOrdersResponse {
  orders: OrderView[];
  nextCursor: string | null;
}

export interface SeatView {
  seatId: string;
  available: boolean;
}

export interface SeatAvailabilityView {
  routeId: string;
  travelDate: string;
  seats: SeatView[];
}

/**
 * Place a checkout. Requires auth + a fresh Idempotency-Key per attempt (reused only on
 * retry of the SAME attempt). The correlationId ties this UI action to the money-path trace.
 */
export function placeOrder(
  body: CheckoutRequest,
  idempotencyKey: string,
  correlationId: string,
  signal?: AbortSignal,
): Promise<CheckoutResponse> {
  return apiFetch<CheckoutResponse>("/orders", {
    method: "POST",
    body,
    auth: true,
    idempotencyKey,
    correlationId,
    signal,
  });
}

export function getOrder(orderId: string, signal?: AbortSignal): Promise<OrderView> {
  return apiFetch<OrderView>(`/orders/${encodeURIComponent(orderId)}`, { auth: true, signal });
}

/** Scoped to the authenticated customer, newest first. Pass back `nextCursor` as `cursor` to page. */
export function listOrders(
  opts: { status?: OrderStatus; cursor?: string | null; limit?: number } = {},
  signal?: AbortSignal,
): Promise<ListOrdersResponse> {
  const params = new URLSearchParams();
  if (opts.status) params.set("status", opts.status);
  if (opts.cursor) params.set("cursor", opts.cursor);
  if (opts.limit) params.set("limit", String(opts.limit));
  const qs = params.toString();
  return apiFetch<ListOrdersResponse>(`/orders${qs ? `?${qs}` : ""}`, { auth: true, signal });
}

/**
 * Advisory seat map for the picker: a seat shown free can still lose the hold race, so
 * `SEATS_UNAVAILABLE` at checkout stays the authoritative answer and must still be handled.
 */
export function getSeatAvailability(
  routeId: string,
  travelDate: string,
  signal?: AbortSignal,
): Promise<SeatAvailabilityView> {
  const params = new URLSearchParams({ routeId, travelDate });
  return apiFetch<SeatAvailabilityView>(`/orders/seat-availability?${params}`, { auth: true, signal });
}

/** Terminal states: polling can stop once one of these is reached. */
export function isTerminal(status: OrderStatus): boolean {
  return status === "CONFIRMED" || status === "FAILED" || status === "CANCELLED";
}
