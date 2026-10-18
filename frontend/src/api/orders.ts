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
  | "COMPENSATING"
  // The post-sale refund process (ADR-015), a separate durable pipeline from the checkout above:
  // reachable only from CONFIRMED, and REFUNDING is where a stuck refund waits for an operator.
  | "REFUNDING"
  | "REFUNDED";

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
  failureCode?: FailureCode | null;
  failureMessage?: string | null;
  currency?: string;
  /** The refund terms frozen at checkout. All null together when the order carries no offer. */
  offerId?: string | null;
  refundable?: boolean | null;
  /** Null when not refundable, and also when refundable right up to departure. */
  refundCutoffMinutesBeforeDeparture?: number | null;
  /** The instant the cutoff counts back from, already composed with the departure station's zone. */
  departureAt?: string | null;
}

/**
 * Asks for a post-sale refund (ADR-015). Needs a fresh `Idempotency-Key` per attempt: replaying an
 * accepted key returns the original answer even past the cutoff, while a new request past it is
 * refused. Answers `202` with the order already `REFUNDING` — the reversal runs asynchronously.
 */
export function refundOrder(
  orderId: string,
  idempotencyKey: string,
  correlationId: string,
  signal?: AbortSignal,
): Promise<OrderView> {
  return apiFetch<OrderView>(`/orders/${encodeURIComponent(orderId)}/refund`, {
    method: "POST",
    auth: true,
    idempotencyKey,
    correlationId,
    signal,
  });
}

export type RefundWindow = { open: true; deadline: number } | { open: false; reason: string };

/** "24 hours before departure", "5 minutes before departure", or departure itself. */
export function cutoffLabel(minutes: number | null | undefined): string {
  if (!minutes) return "departure";
  if (minutes % 1440 === 0) return `${minutes / 1440} day${minutes === 1440 ? "" : "s"} before departure`;
  if (minutes % 60 === 0) return `${minutes / 60} hour${minutes === 60 ? "" : "s"} before departure`;
  return `${minutes} minute${minutes === 1 ? "" : "s"} before departure`;
}

/**
 * Mirrors the server's four refusals so a customer reads the rule instead of meeting a 409.
 * Eligibility is still decided server-side, against the Orders database clock.
 */
export function refundWindow(order: OrderView, now: number): RefundWindow {
  if (order.status === "REFUNDING") return { open: false, reason: "A refund is already running." };
  if (order.status === "REFUNDED") return { open: false, reason: "This order was refunded." };
  if (order.status !== "CONFIRMED") return { open: false, reason: "Only a confirmed order can be refunded." };
  // All three terms are null together on an order sold before the offer fields existed.
  if (order.refundable == null || !order.departureAt) {
    return { open: false, reason: "This order was sold without refund terms." };
  }
  if (!order.refundable) return { open: false, reason: "This fare is non-refundable." };
  const deadline =
    new Date(order.departureAt).getTime() - (order.refundCutoffMinutesBeforeDeparture ?? 0) * 60_000;
  if (now >= deadline) {
    return { open: false, reason: `The refund window closed ${cutoffLabel(order.refundCutoffMinutesBeforeDeparture)}.` };
  }
  return { open: true, deadline };
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

/**
 * Terminal states: polling can stop once one of these is reached. `REFUNDING` is deliberately not
 * one — the reversal and the seat return are still outstanding, so the page keeps watching.
 */
export function isTerminal(status: OrderStatus): boolean {
  return (
    status === "CONFIRMED" || status === "FAILED" || status === "CANCELLED" || status === "REFUNDED"
  );
}
