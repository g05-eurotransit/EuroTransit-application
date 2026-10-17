import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { cancelOrder, createHold, getSeatAvailability } from "../api/orders";
import { ApiError } from "../api/client";
import { SeatSelectionPage } from "../pages/SeatSelectionPage";

vi.mock("../api/orders", () => ({
  getSeatAvailability: vi.fn(),
  createHold: vi.fn(),
  cancelOrder: vi.fn(),
}));

function CheckoutProbe() {
  const { state, search } = useLocation();
  return (
    <>
      <pre data-testid="checkout-state">{JSON.stringify(state)}</pre>
      <pre data-testid="checkout-search">{search}</pre>
    </>
  );
}

function offerState(price = 24.9) {
  return {
    routeId: "R1",
    origin: "Torino",
    destination: "Milano",
    departureTime: "09:00:00",
    arrivalTime: "10:00:00",
    trainType: "Regional",
    offerId: "of-001",
    fareClass: "Standard",
    price,
    currency: "EUR",
    conditions: "",
    travelDate: "2026-10-07",
  };
}

function renderSeats(state: object = offerState()) {
  render(
    <MemoryRouter initialEntries={[{ pathname: "/seats", state }]}>
      <Routes>
        <Route path="/seats" element={<SeatSelectionPage />} />
        <Route path="/checkout" element={<CheckoutProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function pick(...seatIds: string[]) {
  for (const seatId of seatIds) {
    fireEvent.click(await screen.findByRole("button", { name: seatId }));
  }
}

describe("seat selection", () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.mocked(createHold).mockReset();
    vi.mocked(cancelOrder).mockReset();
    vi.mocked(getSeatAvailability).mockResolvedValue({
      routeId: "R1",
      travelDate: "2026-10-07",
      seats: ["1A", "1B", "1C"].map((seatId) => ({ seatId, available: true })),
    });
  });

  it.each([
    [24.9, 74.7],
    [0.1, 0.3],
    [19.99, 59.97],
  ])("holds three seats at %s EUR for an exact total and goes to the payment step", async (price, total) => {
    vi.mocked(createHold).mockResolvedValue({ orderId: "o-1", status: "ACCEPTED" });
    renderSeats(offerState(price));

    const continueButton = screen.getByRole("button", { name: "Continue" });
    expect(continueButton).toBeDisabled();
    await pick("1A", "1B", "1C");
    expect(screen.getByText(`${total.toFixed(2)} EUR`)).toBeInTheDocument();
    fireEvent.click(continueButton);

    const search = await screen.findByTestId("checkout-search");
    expect(search.textContent).toBe("?orderId=o-1");
    const [body] = vi.mocked(createHold).mock.calls[0];
    expect(body).toEqual({ routeId: "R1", seats: ["1A", "1B", "1C"], travelDate: "2026-10-07", totalAmount: total, offerId: "of-001" });
    expect(body).not.toHaveProperty("paymentMethodToken");
    const checkout = JSON.parse(screen.getByTestId("checkout-state").textContent!);
    expect(checkout).toMatchObject({ orderId: "o-1", totalAmount: total, seats: ["1A", "1B", "1C"], price });
  });

  it("reuses the same Idempotency-Key after a lost response, so the seats are never held twice", async () => {
    vi.mocked(createHold)
      .mockRejectedValueOnce(new ApiError("network", 0, "offline"))
      .mockResolvedValueOnce({ orderId: "o-1", status: "ACCEPTED" });
    renderSeats();
    await pick("1A");

    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByText(/could not reach the booking service/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    await screen.findByTestId("checkout-search");

    const [first, second] = vi.mocked(createHold).mock.calls.map((call) => call[1]);
    expect(second).toBe(first);
    expect(sessionStorage.length).toBe(0);
  });

  it("offers to resume or cancel a hold the customer already has", async () => {
    vi.mocked(createHold)
      .mockRejectedValueOnce(new ApiError("conflict", 409, "m", "ACTIVE_HOLD_EXISTS", undefined, { orderId: "o-old" }))
      .mockResolvedValueOnce({ orderId: "o-new", status: "ACCEPTED" });
    vi.mocked(cancelOrder).mockResolvedValue({} as never);
    renderSeats();
    await pick("1A");

    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByText(/already have seats held/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resume" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Cancel it and continue" }));
    const search = await screen.findByTestId("checkout-search");
    expect(cancelOrder).toHaveBeenCalledWith("o-old");
    expect(search.textContent).toBe("?orderId=o-new");
  });

  it("retries once by itself when the blocking hold ended in the meantime", async () => {
    vi.mocked(createHold)
      .mockRejectedValueOnce(new ApiError("conflict", 409, "m", "ACTIVE_HOLD_EXISTS", undefined, { orderId: null }))
      .mockResolvedValueOnce({ orderId: "o-1", status: "ACCEPTED" });
    renderSeats();
    await pick("1A");

    fireEvent.click(screen.getByRole("button", { name: "Continue" }));

    await screen.findByTestId("checkout-search");
    expect(createHold).toHaveBeenCalledTimes(2);
  });

  it("shows why the checkout sent the customer back", async () => {
    renderSeats({ ...offerState(), notice: "Sorry, those seats were just taken. Please choose others." });
    expect(await screen.findByText(/seats were just taken/)).toBeInTheDocument();
    await waitFor(() => expect(getSeatAvailability).toHaveBeenCalled());
  });
});
