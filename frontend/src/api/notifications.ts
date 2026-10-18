import { fetchEventSource } from "@microsoft/fetch-event-source";
import { apiFetch } from "./client";
import { getAccessToken } from "../auth/tokenHolder";
import { getRuntimeConfig } from "../config/runtimeConfig";

export type NotificationType = "ORDER_CONFIRMED" | "ORDER_FAILED";

/** The trip, as the customer recognises it. Times are local to the departure station. */
export interface NotificationRoute {
  routeId: string;
  origin: string;
  destination: string;
  travelDate: string; // ISO LocalDate, e.g. "2026-08-18"
  departureTime: string; // ISO LocalTime, e.g. "07:00:00"
}

export interface NotificationView {
  id: string;
  principalId: string;
  orderId: string;
  /**
   * A complete, ready-to-display sentence, and the only field that says anything about money.
   * Render it verbatim: those claims are owned by the Orders failure-code set, which is careful
   * about what it can truthfully assert, and restating them here would let the two drift.
   */
  message: string;
  recipientEmail: string;
  occurredAt: string;
  receivedAt: string;
  // Optional: a notification produced before this contract carries none of these, and one whose
  // route could not be resolved carries no route. Render the message alone in either case.
  type?: NotificationType;
  /** The Orders failure code. Branch on this, never on an HTTP status. */
  failureCode?: string;
  route?: NotificationRoute;
}

/** Notification history for the authenticated caller (owner-scoped server-side). */
export function listNotifications(signal?: AbortSignal): Promise<NotificationView[]> {
  return apiFetch<NotificationView[]>("/notifications", { auth: true, signal });
}

export interface StreamHandlers {
  onNotification: (n: NotificationView) => void;
  onError?: (e: unknown) => void;
  onOpen?: () => void;
}

/**
 * Subscribe to the live SSE feed.
 *
 * Uses fetch-based SSE (NOT the native EventSource) specifically so we can send the token
 * in an Authorization header — the native EventSource can't set headers, which would force
 * the JWT into the URL query string, forbidden by 07b slide 19 (tokens leak in logs/history).
 *
 * Returns an unsubscribe function. On disconnect the caller should fall back to polling
 * `listNotifications` (api-design.md: SSE is an enhancement over history).
 */
export function subscribeNotifications(handlers: StreamHandlers): () => void {
  const { apiBase } = getRuntimeConfig();
  const controller = new AbortController();
  const token = getAccessToken();

  void fetchEventSource(`${apiBase}/notifications/stream`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal: controller.signal,
    openWhenHidden: true,
    onopen: async () => {
      handlers.onOpen?.();
    },
    onmessage: (ev) => {
      if (!ev.data) return;
      try {
        handlers.onNotification(JSON.parse(ev.data) as NotificationView);
      } catch (e) {
        handlers.onError?.(e);
      }
    },
    onerror: (e) => {
      handlers.onError?.(e);
      // Let fetch-event-source retry; the caller also polls as a fallback.
    },
  });

  return () => controller.abort();
}
