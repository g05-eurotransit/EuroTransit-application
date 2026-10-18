import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getOrder, type OrderView } from "../api/orders";
import { getRoute } from "../api/catalog";
import { OrderStatusPage } from "../pages/OrderStatusPage";

vi.mock("../api/orders", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/orders")>()),
  getOrder: vi.fn(),
}));
vi.mock("../api/catalog", () => ({ getRoute: vi.fn() }));
// The page asks for the operations role to pick its back link; no OIDC provider is mounted here.
vi.mock("../auth/roles", () => ({ useIsOperator: () => false }));

function order(status: OrderView["status"]): OrderView {
  return {
    orderId: "o-1",
    userId: "alice",
    status,
    routeId: "R1",
    seats: ["1A"],
    travelDate: "2026-10-30",
    totalAmount: 24.9,
    createdAt: "2026-10-17T12:00:00",
    reservationExpiresAt: new Date(Date.now() + 9 * 60_000).toISOString(),
    serverNow: new Date().toISOString(),
  };
}

function renderOrder() {
  render(
    <MemoryRouter initialEntries={["/orders/o-1"]}>
      <Routes>
        <Route path="/orders/:orderId" element={<OrderStatusPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("order status page", () => {
  beforeEach(() => {
    vi.mocked(getRoute).mockRejectedValue(new Error("not needed"));
  });

  it("never shows the pipeline's deadline once the customer has paid", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("PAYMENT_PENDING"));
    renderOrder();

    expect(await screen.findByText("PAYMENT_PENDING")).toBeInTheDocument();
    expect(screen.queryByText(/expires in/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\d+:\d\d/)).not.toBeInTheDocument();
  });

  it("offers to complete the payment of a held order", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("HELD_AWAITING_PAYMENT"));
    renderOrder();

    const link = await screen.findByRole("link", { name: "Complete payment" });
    expect(link).toHaveAttribute("href", "/checkout?orderId=o-1");
  });
});
