import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getSeatAvailability } from "../api/orders";
import { SeatSelectionPage } from "../pages/SeatSelectionPage";

vi.mock("../api/orders", () => ({ getSeatAvailability: vi.fn() }));

function CheckoutState() {
  const { state } = useLocation();
  return <pre data-testid="checkout-state">{JSON.stringify(state)}</pre>;
}

describe("seat selection checkout total", () => {
  beforeEach(() => {
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
  ])("passes an exact total to checkout for three seats at %s EUR", async (price, total) => {
    render(
      <MemoryRouter initialEntries={[{
        pathname: "/seats",
        state: {
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
        },
      }]}>
        <Routes>
          <Route path="/seats" element={<SeatSelectionPage />} />
          <Route path="/checkout" element={<CheckoutState />} />
        </Routes>
      </MemoryRouter>,
    );

    const continueButton = screen.getByRole("button", { name: "Continue" });
    expect(continueButton).toBeDisabled();
    for (const seatId of ["1A", "1B", "1C"]) {
      fireEvent.click(await screen.findByRole("button", { name: seatId }));
    }
    expect(screen.getByText(`${total.toFixed(2)} EUR`)).toBeInTheDocument();
    fireEvent.click(continueButton);

    const checkout = JSON.parse(screen.getByTestId("checkout-state").textContent!);
    expect(checkout.totalAmount).toBe(total);
    expect(checkout.seats).toEqual(["1A", "1B", "1C"]);
  });
});
