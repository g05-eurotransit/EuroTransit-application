import { apiFetch } from "./client";
import type { OrderStatus } from "./orders";

/**
 * The operator API over reconciliation incidents (api-design.md, Orders).
 *
 * Every call needs the `operations` realm role; every mutating one a fresh `Idempotency-Key`.
 */

/** The incident kinds, each with its own resolution path (model/ReconciliationRecord.kt). */
export type ReconciliationKind =
  | "DELIVERY_EXHAUSTED"
  | "INVARIANT_VIOLATION"
  | "PAYMENT_REVERSAL_FAILURE"
  | "SEAT_RETURN_FAILURE"
  | "PAYMENT_OUTCOME_UNKNOWN";

/**
 * An open incident as `GET /api/orders/reconciliation` lists it. `orderState` is the phase that
 * failed; `orderStatus` is where the order is now. They differ, so the table shows both.
 */
export interface ReconciliationIncident {
  id: string;
  orderId: string;
  kind: ReconciliationKind;
  orderState: string;
  orderStatus: OrderStatus | null;
  failureClass: string;
  attemptCount: number;
  createdAt: string;
  updatedAt: string;
  /** The seat-hold deadline, when the order has one. */
  reservationExpiresAt?: string | null;
}

export interface RedriveResponse {
  orderId: string;
  orderStatus: OrderStatus;
  attemptCount: number;
}

export interface AbandonResponse {
  orderId: string;
  orderStatus: OrderStatus;
}

export interface SettleResponse {
  orderId: string;
  recordId: string;
  orderStatus: OrderStatus;
}

export interface ResolveResponse {
  orderId: string;
  recordId: string;
  kind: ReconciliationKind;
  status: string;
  resolvedAt: string;
}

/** Open incidents, most urgent first (live seat hold and nearest deadline first). Read only. */
export function listOpenIncidents(
  limit = 100,
  signal?: AbortSignal,
): Promise<ReconciliationIncident[]> {
  return apiFetch<ReconciliationIncident[]>(`/orders/reconciliation?limit=${limit}`, {
    auth: true,
    signal,
  });
}

/**
 * Writes a fresh outbox row and nothing else: `order-placed` for a checkout, `refund-requested` for
 * a REFUNDING order — which is how a stuck refund's reversal and seat return get retried.
 */
export function redriveOrder(
  orderId: string,
  idempotencyKey: string,
  correlationId: string,
): Promise<RedriveResponse> {
  return apiFetch<RedriveResponse>(
    `/orders/${encodeURIComponent(orderId)}/reconciliation/redrive`,
    { method: "POST", auth: true, idempotencyKey, correlationId },
  );
}

/**
 * Gives up a PAYMENT_PENDING order past its seat hold: it moves to COMPENSATING and the pipeline
 * reverses the payment and fails it. With no settled authorization it parks on
 * PAYMENT_OUTCOME_UNKNOWN instead — a missing row does not prove no money was taken.
 */
export function abandonOrder(
  orderId: string,
  idempotencyKey: string,
  correlationId: string,
): Promise<AbandonResponse> {
  return apiFetch<AbandonResponse>(
    `/orders/${encodeURIComponent(orderId)}/reconciliation/abandon`,
    { method: "POST", auth: true, idempotencyKey, correlationId },
  );
}

/** Settles an unknown payment outcome: the operator attests the provider holds no money. */
export function settleIncident(
  orderId: string,
  recordId: string,
  evidenceReference: string,
  idempotencyKey: string,
  correlationId: string,
): Promise<SettleResponse> {
  return apiFetch<SettleResponse>(
    `/orders/${encodeURIComponent(orderId)}/reconciliation/${encodeURIComponent(recordId)}/settle`,
    { method: "POST", auth: true, body: { evidenceReference }, idempotencyKey, correlationId },
  );
}

/** Closes an investigated invariant violation on an already-terminal order, with its evidence. */
export function resolveIncident(
  orderId: string,
  recordId: string,
  evidenceReference: string,
  idempotencyKey: string,
  correlationId: string,
): Promise<ResolveResponse> {
  return apiFetch<ResolveResponse>(
    `/orders/${encodeURIComponent(orderId)}/reconciliation/${encodeURIComponent(recordId)}/resolve`,
    { method: "POST", auth: true, body: { evidenceReference }, idempotencyKey, correlationId },
  );
}

export function currentStatus(incident: ReconciliationIncident): string {
  return incident.orderStatus ?? incident.orderState;
}

export type IncidentAction = "redrive" | "abandon" | "settle" | "resolve";

/** Duplicates the runbook's kind x status table so the operator meets the rule, not the 409. */
export function allowedAction(
  incident: ReconciliationIncident,
  holdExpired: boolean,
): { action: IncidentAction; hint: string } | { action: null; reason: string } {
  const status = currentStatus(incident);

  if (status === "REFUNDING") {
    // A refund whose money or seat is still unresolved: a redrive resumes that pipeline.
    if (
      incident.kind === "DELIVERY_EXHAUSTED" ||
      incident.kind === "PAYMENT_REVERSAL_FAILURE" ||
      incident.kind === "SEAT_RETURN_FAILURE"
    ) {
      return { action: "redrive", hint: "Resumes the refund: retries the reversal and the seat return." };
    }
  }

  if (incident.kind === "PAYMENT_OUTCOME_UNKNOWN") {
    if (status === "COMPENSATING") {
      return {
        action: "settle",
        hint: "Check the provider first: settling attests that it holds no money for this order.",
      };
    }
    return { action: null, reason: "Waits for the abandoned checkout to reach COMPENSATING." };
  }

  if (incident.kind === "INVARIANT_VIOLATION") {
    if (status === "FAILED" || status === "CANCELLED" || status === "CONFIRMED" || status === "REFUNDED") {
      return { action: "resolve", hint: "Investigate first: this closes the incident with your evidence." };
    }
    return { action: null, reason: "Do not redrive. Investigate; resolvable once the order is terminal." };
  }

  if (incident.kind === "PAYMENT_REVERSAL_FAILURE") {
    if (status === "COMPENSATING") {
      return { action: "redrive", hint: "Retries the payment reversal the provider refused." };
    }
    return { action: null, reason: `No runbook action for ${incident.kind} in ${status}.` };
  }

  if (incident.kind === "DELIVERY_EXHAUSTED") {
    if (status === "PAYMENT_PENDING") {
      return holdExpired
        ? {
            action: "abandon",
            hint: "The seat hold is gone, so a redrive would only buy a charge and its reversal.",
          }
        : { action: "redrive", hint: "Redrive before the hold expires." };
    }
    if (
      status === "ACCEPTED" ||
      status === "RESERVING" ||
      status === "CONFIRMING_INVENTORY" ||
      status === "COMPENSATING"
    ) {
      return { action: "redrive", hint: "Redrive once the cause of the dead-lettered delivery is gone." };
    }
    return { action: null, reason: `No runbook action for ${incident.kind} in ${status}.` };
  }

  return { action: null, reason: `No runbook action for ${incident.kind} in ${status}.` };
}

/** The two actions that attest to something and are refused without an evidence reference. */
export function needsEvidence(action: IncidentAction): boolean {
  return action === "settle" || action === "resolve";
}

/** The frozen refusal codes (api-design.md) in an operator's words. */
const REFUSALS: Record<string, string> = {
  ORDER_NOT_FOUND: "That order no longer exists.",
  ORDER_ALREADY_TERMINAL:
    "The order is already settled, so there is nothing to resume; its stale incident was closed.",
  RESERVATION_HOLD_EXPIRED:
    "The seat hold has expired: a redrive is refused for a pending payment. Abandon the order instead.",
  RESERVATION_HOLD_NOT_EXPIRED: "The seat hold still runs — redrive instead of abandoning.",
  ORDER_NOT_ABANDONABLE: "Only a pending-payment order past its seat hold can be abandoned.",
  NO_OPEN_RECONCILIATION_RECORD: "The incident is already closed — refresh the list.",
  EVIDENCE_REFERENCE_REQUIRED: "An evidence reference is required.",
  RECONCILIATION_RECORD_NOT_FOUND: "That incident does not exist on this order.",
  RECONCILIATION_KIND_NOT_SETTLEABLE: "Only an unknown payment outcome can be settled.",
  RECONCILIATION_KIND_NOT_RESOLVABLE: "That kind closes on its own; it cannot be resolved by hand.",
  RECONCILIATION_ALREADY_RESOLVED: "The incident was already resolved — refresh the list.",
  ORDER_NOT_SETTLEABLE: "The order is not in compensation, so its payment outcome cannot be settled.",
  ORDER_NOT_TERMINAL: "The order is not terminal yet: its money question is still open.",
};

export function refusalMessage(code: string | undefined, fallback: string): string {
  return (code && REFUSALS[code]) ?? fallback;
}
