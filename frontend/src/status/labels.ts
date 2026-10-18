import type { OrderStatus } from "../api/orders";

/**
 * Where an `OrderStatus` becomes words a customer can read: `CONFIRMING_INVENTORY` is the
 * pipeline's name for a phase, not a sentence. Green for done, amber in flight, red for a fault.
 */

export interface StatusLabel {
  /** The badge text. */
  label: string;
  /** Bootstrap background variant. */
  bg: string;
  /** Bootstrap text variant, when the background is a subtle one. */
  text?: string;
  /** What is happening, in the present tense, for the line under the stepper. */
  sentence: string;
}

const LABELS: Record<OrderStatus, StatusLabel> = {
  ACCEPTED: {
    label: "Placing",
    bg: "warning",
    sentence: "We have your order and are starting on it.",
  },
  RESERVING: {
    label: "Holding seats",
    bg: "warning",
    sentence: "We are holding your seats.",
  },
  HELD_AWAITING_PAYMENT: {
    label: "Awaiting payment",
    bg: "warning",
    sentence: "Your seats are held and waiting for your payment.",
  },
  PAYMENT_PENDING: {
    label: "Paying",
    bg: "warning",
    sentence: "We are authorising the payment. You can close this page — the order carries on.",
  },
  CONFIRMING_INVENTORY: {
    label: "Confirming",
    bg: "warning",
    sentence: "The payment went through; we are confirming the seats.",
  },
  CONFIRMED: {
    label: "Confirmed",
    bg: "success",
    sentence: "Your booking is confirmed.",
  },
  COMPENSATING: {
    label: "Resolving payment",
    bg: "warning",
    sentence: "We are resolving the payment for this order. No further action is needed from you.",
  },
  FAILED: {
    label: "Failed",
    bg: "danger",
    sentence: "This order could not be completed.",
  },
  CANCELLED: {
    label: "Cancelled",
    bg: "danger",
    sentence: "This order was cancelled.",
  },
  REFUNDING: {
    label: "Refunding",
    bg: "warning",
    sentence: "Refund in progress: the money goes back first, then the seat returns to sale.",
  },
  REFUNDED: {
    label: "Refunded",
    bg: "accent-subtle",
    text: "accent-emphasis",
    sentence: "This ticket was refunded.",
  },
};

export function statusLabel(status: OrderStatus): StatusLabel {
  return LABELS[status] ?? { label: status, bg: "secondary", sentence: "" };
}
