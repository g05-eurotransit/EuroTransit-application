import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  abandonOrder,
  allowedAction,
  listOpenIncidents,
  redriveOrder,
  settleIncident,
  type ReconciliationIncident,
} from "../api/reconciliation";
import { ApiError } from "../api/client";
import { OperationsPage } from "../pages/OperationsPage";

vi.mock("../api/reconciliation", async (importOriginal) => {
  // Only the calls that would hit Orders are stubbed; the matrix runs for real.
  const actual = await importOriginal<typeof import("../api/reconciliation")>();
  return {
    ...actual,
    listOpenIncidents: vi.fn(),
    redriveOrder: vi.fn(),
    abandonOrder: vi.fn(),
    settleIncident: vi.fn(),
    resolveIncident: vi.fn(),
  };
});

const ORDER_ID = "11111111-2222-3333-4444-555555555555";

function incident(overrides: Partial<ReconciliationIncident> = {}): ReconciliationIncident {
  return {
    id: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    orderId: ORDER_ID,
    kind: "DELIVERY_EXHAUSTED",
    orderState: "PAYMENT_PENDING",
    orderStatus: "PAYMENT_PENDING",
    failureClass: "dependency_error",
    attemptCount: 3,
    createdAt: "2026-10-17T09:00:00Z",
    updatedAt: "2026-10-17T09:00:00Z",
    reservationExpiresAt: null,
    ...overrides,
  };
}

describe("the runbook action matrix", () => {
  it("offers a redrive on a pending payment while the seat hold runs, and an abandon after it", () => {
    const pending = incident({ reservationExpiresAt: "2026-10-17T09:10:00Z" });
    expect(allowedAction(pending, false)).toMatchObject({ action: "redrive" });
    expect(allowedAction(pending, true)).toMatchObject({ action: "abandon" });
  });

  it("never offers a redrive on an invariant violation", () => {
    const violation = incident({ kind: "INVARIANT_VIOLATION", orderState: "COMPENSATING", orderStatus: "FAILED" });
    expect(allowedAction(violation, false)).toMatchObject({ action: "resolve" });
  });

  it("redrives a stuck refund, which is what retries the reversal and the seat return", () => {
    for (const kind of ["PAYMENT_REVERSAL_FAILURE", "SEAT_RETURN_FAILURE", "DELIVERY_EXHAUSTED"] as const) {
      const refunding = incident({ kind, orderState: "REFUNDING", orderStatus: "REFUNDING" });
      expect(allowedAction(refunding, false)).toMatchObject({ action: "redrive" });
    }
  });

  it("settles an unknown payment outcome instead of redriving it", () => {
    const unknown = incident({
      kind: "PAYMENT_OUTCOME_UNKNOWN",
      orderState: "COMPENSATING",
      orderStatus: "COMPENSATING",
    });
    expect(allowedAction(unknown, false)).toMatchObject({ action: "settle" });
  });
});

describe("the operations console", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("redrives the order the row belongs to and reloads the list", async () => {
    vi.mocked(listOpenIncidents)
      .mockResolvedValueOnce([incident({ orderState: "RESERVING", orderStatus: "RESERVING" })])
      .mockResolvedValueOnce([]);
    vi.mocked(redriveOrder).mockResolvedValue({ orderId: ORDER_ID, orderStatus: "RESERVING", attemptCount: 4 });

    render(
      <MemoryRouter>
        <OperationsPage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "Redrive" }));

    await waitFor(() => expect(redriveOrder).toHaveBeenCalledTimes(1));
    const [orderId, idempotencyKey, correlationId] = vi.mocked(redriveOrder).mock.calls[0]!;
    expect(orderId).toBe(ORDER_ID);
    expect(idempotencyKey).not.toBe(correlationId);
    expect(await screen.findByText(/No open incidents/)).toBeInTheDocument();
  });

  it("keeps the order's action disabled until the pipeline has had a moment", async () => {
    // The command answers 202 before the pipeline has done anything, so the reload that follows
    // can still show the incident it is about to close.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(listOpenIncidents).mockResolvedValue([
        incident({ orderState: "RESERVING", orderStatus: "RESERVING" }),
      ]);
      vi.mocked(redriveOrder).mockResolvedValue({ orderId: ORDER_ID, orderStatus: "RESERVING", attemptCount: 2 });

      render(
        <MemoryRouter>
          <OperationsPage />
        </MemoryRouter>,
      );

      fireEvent.click(await screen.findByRole("button", { name: "Redrive" }));

      // Reloaded straight away, but the row stays locked: the pipeline is still working.
      await waitFor(() => expect(listOpenIncidents).toHaveBeenCalledTimes(2));
      expect(await screen.findByText("Waiting for the pipeline…")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Redrive" })).not.toBeInTheDocument();

      // Advancing the timers also fires the table's one-second clock: every tick is a state update.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000);
      });
      await waitFor(() => expect(listOpenIncidents).toHaveBeenCalledTimes(3));
      expect(await screen.findByRole("button", { name: "Redrive" })).toBeEnabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("clears a success notice on its own, and keeps a refusal on screen", async () => {
    // A refusal is the only place the reason and the correlation id appear.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(listOpenIncidents).mockResolvedValue([
        incident({ orderState: "RESERVING", orderStatus: "RESERVING" }),
      ]);
      vi.mocked(redriveOrder).mockResolvedValueOnce({
        orderId: ORDER_ID,
        orderStatus: "RESERVING",
        attemptCount: 2,
      });

      render(
        <MemoryRouter>
          <OperationsPage />
        </MemoryRouter>,
      );

      fireEvent.click(await screen.findByRole("button", { name: "Redrive" }));
      expect(await screen.findByText(/redriven from RESERVING/)).toBeInTheDocument();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(9000);
      });
      expect(screen.queryByText(/redriven from RESERVING/)).not.toBeInTheDocument();

      vi.mocked(redriveOrder).mockRejectedValueOnce(
        new ApiError("conflict", 409, "already terminal", "ORDER_ALREADY_TERMINAL", "corr-1"),
      );
      fireEvent.click(await screen.findByRole("button", { name: "Redrive" }));
      expect(await screen.findByText(/already settled/)).toBeInTheDocument();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(9000);
      });
      expect(screen.getByText(/already settled/)).toBeInTheDocument();
      expect(screen.getByText(/corr-1/)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("abandons a pending payment whose hold has already lapsed", async () => {
    vi.mocked(listOpenIncidents).mockResolvedValue([
      incident({ reservationExpiresAt: "2020-01-01T00:00:00Z" }),
    ]);
    vi.mocked(abandonOrder).mockResolvedValue({ orderId: ORDER_ID, orderStatus: "COMPENSATING" });

    render(
      <MemoryRouter>
        <OperationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText("hold expired")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Abandon" }));
    await waitFor(() => expect(abandonOrder).toHaveBeenCalledTimes(1));
  });

  it("shows no seat-hold clock on an order that is no longer racing one", async () => {
    // A CONFIRMED order keeps the deadline its hold once had, necessarily in the past.
    vi.mocked(listOpenIncidents).mockResolvedValue([
      incident({
        kind: "INVARIANT_VIOLATION",
        orderState: "COMPENSATING",
        orderStatus: "CONFIRMED",
        reservationExpiresAt: "2020-01-01T00:00:00Z",
      }),
    ]);

    render(
      <MemoryRouter>
        <OperationsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByRole("button", { name: "Resolve…" })).toBeInTheDocument();
    expect(screen.queryByText("hold expired")).not.toBeInTheDocument();
  });

  it("refreshes itself on a timer, and stops while a dialog is open", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(listOpenIncidents).mockResolvedValue([
        incident({ kind: "PAYMENT_OUTCOME_UNKNOWN", orderState: "COMPENSATING", orderStatus: "COMPENSATING" }),
      ]);

      render(
        <MemoryRouter>
          <OperationsPage />
        </MemoryRouter>,
      );

      await screen.findByRole("button", { name: "Settle…" });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(31_000);
      });
      await waitFor(() => expect(listOpenIncidents).toHaveBeenCalledTimes(2));

      // With the evidence dialog open the list must hold still: the row behind it is the one the
      // operator is answering about.
      fireEvent.click(screen.getByRole("button", { name: "Settle…" }));
      await screen.findByRole("dialog");
      await act(async () => {
        await vi.advanceTimersByTimeAsync(61_000);
      });
      expect(listOpenIncidents).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("will not settle an unknown payment outcome without an evidence reference", async () => {
    vi.mocked(listOpenIncidents).mockResolvedValue([
      incident({ kind: "PAYMENT_OUTCOME_UNKNOWN", orderState: "COMPENSATING", orderStatus: "COMPENSATING" }),
    ]);
    vi.mocked(settleIncident).mockResolvedValue({
      orderId: ORDER_ID,
      recordId: incident().id,
      orderStatus: "COMPENSATING",
    });

    render(
      <MemoryRouter>
        <OperationsPage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "Settle…" }));
    // The row button and the modal's confirm share a label, so the modal is queried on its own.
    const dialog = within(await screen.findByRole("dialog"));
    expect(dialog.getByRole("button", { name: "Settle" })).toBeDisabled();
    expect(settleIncident).not.toHaveBeenCalled();

    fireEvent.change(dialog.getByLabelText("Evidence reference"), { target: { value: "psp-ref-42" } });
    fireEvent.click(dialog.getByRole("button", { name: "Settle" }));

    await waitFor(() => expect(settleIncident).toHaveBeenCalledTimes(1));
    expect(vi.mocked(settleIncident).mock.calls[0]![2]).toBe("psp-ref-42");
  });
});
