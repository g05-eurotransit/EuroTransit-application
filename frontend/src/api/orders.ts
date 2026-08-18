import { apiFetch } from "./client";

export type OrderStatus =
  | "ACCEPTED"
  | "RESERVING"
  | "PAYMENT_PENDING"
  | "CONFIRMED"
  | "FAILED"
  | "CANCELLED";

export interface CheckoutRequest {
  routeId: string;
  seats: string[];
  totalAmount: number;
  // Added by the Payments slice: a provider sandbox test token (e.g. "tok_visa").
  paymentMethodToken: string;
}

export interface CheckoutResponse {
  orderId: string;
  status: OrderStatus;
}

export interface OrderView {
  orderId: string;
  userId: string;
  status: OrderStatus;
  routeId: string;
  seats: string[];
  totalAmount: number;
  createdAt: string;
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

/** Terminal states: polling can stop once one of these is reached. */
export function isTerminal(status: OrderStatus): boolean {
  return status === "CONFIRMED" || status === "FAILED" || status === "CANCELLED";
}
