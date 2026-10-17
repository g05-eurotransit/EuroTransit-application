import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { cutoffLabel, getOrder, refundOrder, refundWindow, type OrderView } from "../api/orders";
import { ApiError } from "../api/client";
import { getRoute } from "../api/catalog";
import { OrderStatusPage } from "../pages/OrderStatusPage";

vi.mock("../api/orders", async (importOriginal) => {
  // The window rule is a pure function worth running for real; only the two calls are stubbed.
  const actual = await importOriginal<typeof import("../api/orders")>();
  return { ...actual, getOrder: vi.fn(), refundOrder: vi.fn() };
});
vi.mock("../api/catalog", () => ({ getRoute: vi.fn() }));
vi.mock("../auth/roles", () => ({ useIsOperator: () => false }));

const ORDER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
const DEPARTURE = "2026-12-24T19:20:00+01:00";

/** The Nightjet sleeper of the catalogue: refundable up to 24 hours before departure. */
function order(overrides: Partial<OrderView> = {}): OrderView {
  return {
    orderId: ORDER_ID,
    userId: "alice",
    status: "CONFIRMED",
    routeId: "rt-ro-vi-004",
    seats: ["12A"],
    travelDate: "2026-12-24",
    totalAmount: 129,
    currency: "EUR",
    createdAt: "2026-12-01T10:00:00",
    offerId: "of-005",
    refundable: true,
    refundCutoffMinutesBeforeDeparture: 1440,
    departureAt: DEPARTURE,
    ...overrides,
  };
}

const before = new Date("2026-12-20T10:00:00+01:00").getTime();
const after = new Date("2026-12-24T10:00:00+01:00").getTime();

describe("the refund window", () => {
  it("is open until the cutoff and closed after it", () => {
    expect(refundWindow(order(), before)).toMatchObject({ open: true });
    expect(refundWindow(order(), after)).toMatchObject({ open: false });
  });

  it("is closed for a non-refundable fare, whatever the clock says", () => {
    const standard = order({ refundable: false, refundCutoffMinutesBeforeDeparture: null, offerId: "of-001" });
    expect(refundWindow(standard, before)).toMatchObject({ open: false, reason: "This fare is non-refundable." });
  });

  it("is closed for an order sold without refund terms", () => {
    const legacy = order({ offerId: null, refundable: null, refundCutoffMinutesBeforeDeparture: null, departureAt: null });
    expect(refundWindow(legacy, before)).toMatchObject({ open: false });
  });

  it.each(["ACCEPTED", "COMPENSATING", "FAILED", "REFUNDING", "REFUNDED"] as const)(
    "is closed for an order in %s",
    (status) => {
      expect(refundWindow(order({ status }), before)).toMatchObject({ open: false });
    },
  );

  it("reads a cutoff in the unit it was written in", () => {
    expect(cutoffLabel(1440)).toBe("1 day before departure");
    expect(cutoffLabel(120)).toBe("2 hours before departure");
    expect(cutoffLabel(5)).toBe("5 minutes before departure");
    expect(cutoffLabel(null)).toBe("departure");
  });
});

describe("the refund button", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.setSystemTime(before);
    vi.mocked(getRoute).mockResolvedValue({
      routeId: "rt-ro-vi-004",
      origin: "Roma Termini",
      destination: "Wien Hbf",
      departureTime: "19:20:00",
      arrivalTime: "08:42:00",
      trainType: "Nightjet",
    } as never);
  });

  /** The arrival from "My tickets" — the only one that offers the refund. */
  function renderPage(state: unknown = { fromTickets: true }) {
    return render(
      <MemoryRouter initialEntries={[{ pathname: `/orders/${ORDER_ID}`, state }]}>
        <Routes>
          <Route path="/orders/:orderId" element={<OrderStatusPage />} />
        </Routes>
      </MemoryRouter>,
    );
  }

  it("asks for confirmation before refunding, then shows the refund running", async () => {
    // The acceptance is committed before the 202 answers, so the poll that follows sees REFUNDING.
    vi.mocked(getOrder).mockResolvedValueOnce(order()).mockResolvedValue(order({ status: "REFUNDING" }));
    vi.mocked(refundOrder).mockResolvedValue(order({ status: "REFUNDING" }));

    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Refund this ticket" }));
    const dialog = within(await screen.findByRole("dialog"));
    expect(dialog.getByText(/129.00 EUR goes back/)).toBeInTheDocument();
    expect(refundOrder).not.toHaveBeenCalled();

    fireEvent.click(dialog.getByRole("button", { name: "Refund" }));

    await waitFor(() => expect(refundOrder).toHaveBeenCalledTimes(1));
    const [orderId, idempotencyKey, correlationId] = vi.mocked(refundOrder).mock.calls[0]!;
    expect(orderId).toBe(ORDER_ID);
    expect(idempotencyKey).not.toBe(correlationId);
    expect(await screen.findByText(/Refund in progress/)).toBeInTheDocument();
  });

  it("offers no button on a non-refundable fare, and says why", async () => {
    vi.mocked(getOrder).mockResolvedValue(
      order({ refundable: false, refundCutoffMinutesBeforeDeparture: null, offerId: "of-001" }),
    );

    renderPage();

    expect(await screen.findByText("This fare is non-refundable.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Refund this ticket" })).not.toBeInTheDocument();
  });

  it("offers no button on the confirmation screen, but still states the terms", async () => {
    // The arrival the checkout produces carries no history state.
    vi.mocked(getOrder).mockResolvedValue(order());

    renderPage(null);

    expect(
      await screen.findByText(`Refundable until ${cutoffLabel(1440)}`, { exact: false }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Refund this ticket" })).not.toBeInTheDocument();
  });

  it("shows the server's refusal when it disagrees with the page", async () => {
    // The page stops polling at CONFIRMED, so a window that lapses while it sits open still shows
    // its button. The database clock decides, and its answer is what the customer must read.
    vi.mocked(getOrder).mockResolvedValue(order());
    vi.mocked(refundOrder).mockRejectedValue(
      new ApiError("conflict", 409, "cutoff passed", "REFUND_CUTOFF_PASSED", "corr-9"),
    );

    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Refund this ticket" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Refund" }));

    expect(await screen.findByText("The refund window for this ticket has closed.")).toBeInTheDocument();
  });
});
