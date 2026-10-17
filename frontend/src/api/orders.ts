import { apiFetch } from "./client";

export type OrderStatus =
  | "ACCEPTED"
  | "RESERVING"
  // Customer-hold flow (ADR-016): seats held, waiting for the customer to pay before the deadline.
  | "HELD_AWAITING_PAYMENT"
  | "PAYMENT_PENDING"
  | "CONFIRMING_INVENTORY"
  | "CONFIRMED"
  | "FAILED"
  | "CANCELLED"
  | "COMPENSATING";

/**
 * What "Continue" sends. Deliberately no payment token: without one, Orders holds the seats and waits
 * for [submitPayment] (ADR-016). The token-carrying direct flow is for the load test and canary jobs.
 */
export interface HoldRequest {
  routeId: string;
  seats: string[];
  travelDate: string;
  totalAmount: number;
  offerId: string;
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
  /**
   * In HELD_AWAITING_PAYMENT: the customer's deadline to pay (T1), shown as a countdown. After the
   * payment it is the pipeline's own deadline (T2), which is never shown to the customer.
   */
  reservationExpiresAt?: string | null;
  /** The server's clock when it answered, so the countdown does not trust the browser's clock. */
  serverNow?: string | null;
  offerId?: string | null;
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
 * "Continue": creates the order and has Orders hold the seats (ADR-016). Answers 202 at once; the hold
 * itself is taken asynchronously, so the caller polls [getOrder] until HELD_AWAITING_PAYMENT (or
 * FAILED when the seats were taken). A fresh Idempotency-Key per attempt, reused only to retry it.
 */
export function createHold(
  body: HoldRequest,
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

/** "Pay now": the Stripe token for a held order. 202 with PAYMENT_PENDING; the rest is asynchronous. */
export function submitPayment(
  orderId: string,
  paymentMethodToken: string,
  idempotencyKey: string,
  correlationId: string,
  signal?: AbortSignal,
): Promise<CheckoutResponse> {
  return apiFetch<CheckoutResponse>(`/orders/${encodeURIComponent(orderId)}/payment`, {
    method: "POST",
    body: { paymentMethodToken },
    auth: true,
    idempotencyKey,
    correlationId,
    signal,
  });
}

/** Back / change seat: cancels an unpaid order and releases its seats. */
export function cancelOrder(orderId: string, signal?: AbortSignal): Promise<OrderView> {
  return apiFetch<OrderView>(`/orders/${encodeURIComponent(orderId)}/cancel`, {
    method: "POST",
    auth: true,
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

/**
 * Whether the payment of a customer-hold order has started or finished. Asked after a cancel answered
 * 409 ORDER_NOT_CANCELLABLE, because that one answer covers two different things: a payment that got
 * in first, and an order that is already cancelled (the expiry job) or failed.
 */
export function isPaymentUnderway(status: OrderStatus): boolean {
  return (
    status === "PAYMENT_PENDING" ||
    status === "CONFIRMING_INVENTORY" ||
    status === "COMPENSATING" ||
    status === "CONFIRMED"
  );
}

/** Terminal states: polling can stop once one of these is reached. */
export function isTerminal(status: OrderStatus): boolean {
  return status === "CONFIRMED" || status === "FAILED" || status === "CANCELLED";
}
