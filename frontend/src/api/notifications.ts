import { fetchEventSource } from "@microsoft/fetch-event-source";
import { apiFetch } from "./client";
import { getAccessToken } from "../auth/tokenHolder";

const API_BASE = (import.meta.env.VITE_API_BASE as string) ?? "/api";

export interface NotificationView {
  id: string;
  principalId: string;
  orderId: string;
  message: string;
  recipientEmail: string;
  occurredAt: string;
  receivedAt: string;
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
  const controller = new AbortController();
  const token = getAccessToken();

  void fetchEventSource(`${API_BASE}/notifications/stream`, {
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
