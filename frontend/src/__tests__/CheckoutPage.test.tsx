import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { MemoryRouter, Route, Routes, useLocation, useParams } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { cancelOrder, getOrder, submitPayment, type OrderView } from "../api/orders";
import { getRoute, listOffers } from "../api/catalog";
import { ApiError } from "../api/client";
import { CheckoutPage } from "../pages/CheckoutPage";

vi.mock("../api/orders", () => ({ getOrder: vi.fn(), submitPayment: vi.fn(), cancelOrder: vi.fn() }));
vi.mock("../api/catalog", () => ({ getRoute: vi.fn(), listOffers: vi.fn() }));
vi.mock("../config/runtimeConfig", () => ({ getRuntimeConfig: () => ({ stripePublishableKey: "pk_test", apiBase: "/api" }) }));
vi.mock("react-oidc-context", () => ({ useAuth: () => ({ user: { profile: { email: "alice@example.com" } } }) }));
vi.mock("@stripe/stripe-js", () => ({ loadStripe: vi.fn(() => Promise.resolve(null)) }));

const createToken = vi.fn();
vi.mock("@stripe/react-stripe-js", () => {
  // Each card field reports itself complete as soon as it mounts, so "Pay now" can be enabled.
  function Field({ onChange }: { onChange?: (e: { complete: boolean }) => void }) {
    useEffect(() => onChange?.({ complete: true }), []);
    return null;
  }
  return {
    Elements: ({ children }: { children: React.ReactNode }) => <>{children}</>,
    useStripe: () => ({ createToken }),
    useElements: () => ({ getElement: () => ({}) }),
    CardNumberElement: Field,
    CardExpiryElement: Field,
    CardCvcElement: Field,
  };
});

const OFFER_STATE = {
  routeId: "R1",
  origin: "Torino",
  destination: "Milano",
  departureTime: "09:00:00",
  arrivalTime: "10:00:00",
  trainType: "Regional",
  fareClass: "Standard",
  travelDate: "2026-10-30",
  seats: ["1A"],
  totalAmount: 24.9,
  currency: "EUR",
  offerId: "of-001",
  price: 24.9,
  conditions: "",
  orderId: "o-1",
};

function order(status: OrderView["status"], overrides: Partial<OrderView> = {}): OrderView {
  const now = new Date();
  return {
    orderId: "o-1",
    userId: "alice",
    status,
    routeId: "R1",
    seats: ["1A"],
    travelDate: "2026-10-30",
    totalAmount: 24.9,
    createdAt: now.toISOString(),
    reservationExpiresAt: new Date(now.getTime() + 9 * 60_000).toISOString(),
    serverNow: now.toISOString(),
    offerId: "of-001",
    ...overrides,
  };
}

function SeatsProbe() {
  const { state } = useLocation();
  return <pre data-testid="seats-state">{JSON.stringify(state)}</pre>;
}

function OrderProbe() {
  const { orderId } = useParams();
  return <p>order page {orderId}</p>;
}

function renderCheckout(state: object | null = OFFER_STATE) {
  render(
    <MemoryRouter initialEntries={[{ pathname: "/checkout", search: "?orderId=o-1", state }]}>
      <Routes>
        <Route path="/" element={<p>home</p>} />
        <Route path="/checkout" element={<CheckoutPage />} />
        <Route path="/checkout/seats" element={<SeatsProbe />} />
        <Route path="/orders/:orderId" element={<OrderProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function seatsNotice(): Promise<string | undefined> {
  const probe = await screen.findByTestId("seats-state");
  return JSON.parse(probe.textContent!).notice;
}

describe("checkout payment step", () => {
  beforeEach(() => {
    vi.mocked(getOrder).mockReset();
    vi.mocked(submitPayment).mockReset();
    vi.mocked(cancelOrder).mockReset().mockResolvedValue(order("CANCELLED"));
    createToken.mockReset().mockResolvedValue({ token: { id: "tok_visa" } });
  });

  it("waits for the hold, then shows the payment form with the T1 countdown", async () => {
    vi.mocked(getOrder).mockResolvedValueOnce(order("RESERVING")).mockResolvedValue(order("HELD_AWAITING_PAYMENT"));
    renderCheckout();

    expect(await screen.findByText(/Holding your seats/)).toBeInTheDocument();
    expect(await screen.findByText("Seats held for", {}, { timeout: 3000 })).toBeInTheDocument();
    expect(screen.getByRole("timer").textContent).toMatch(/[89]:\d\d/);
    expect(screen.getByRole("button", { name: "Pay now" })).toBeInTheDocument();
  });

  it("goes back to the seat map when the seats were taken", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("FAILED", { failureCode: "SEATS_UNAVAILABLE" }));
    renderCheckout();

    expect(await seatsNotice()).toMatch(/just taken/);
  });

  it("pays with the Stripe token and follows the order", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("HELD_AWAITING_PAYMENT"));
    vi.mocked(submitPayment).mockResolvedValue({ orderId: "o-1", status: "PAYMENT_PENDING" });
    renderCheckout();

    const pay = await screen.findByRole("button", { name: "Pay now" });
    await waitFor(() => expect(pay).toBeEnabled());
    fireEvent.click(pay);

    expect(await screen.findByText("order page o-1")).toBeInTheDocument();
    expect(vi.mocked(submitPayment).mock.calls[0].slice(0, 2)).toEqual(["o-1", "tok_visa"]);
  });

  it("sends the customer back to the seats when the server says time ran out", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("HELD_AWAITING_PAYMENT"));
    vi.mocked(submitPayment).mockRejectedValue(new ApiError("conflict", 409, "m", "HOLD_EXPIRED"));
    renderCheckout();

    const pay = await screen.findByRole("button", { name: "Pay now" });
    await waitFor(() => expect(pay).toBeEnabled());
    fireEvent.click(pay);

    expect(await seatsNotice()).toMatch(/ran out/);
  });

  it("follows the order when another tab already paid", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("HELD_AWAITING_PAYMENT"));
    vi.mocked(submitPayment).mockRejectedValue(new ApiError("conflict", 409, "m", "PAYMENT_ALREADY_STARTED"));
    renderCheckout();

    const pay = await screen.findByRole("button", { name: "Pay now" });
    await waitFor(() => expect(pay).toBeEnabled());
    fireEvent.click(pay);

    expect(await screen.findByText("order page o-1")).toBeInTheDocument();
  });

  it("goes straight to the order when the payment already started", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("PAYMENT_PENDING"));
    renderCheckout();

    expect(await screen.findByText("order page o-1")).toBeInTheDocument();
  });

  it("cancels the order when the customer goes back", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("HELD_AWAITING_PAYMENT"));
    renderCheckout();
    await screen.findByRole("button", { name: "Pay now" });

    fireEvent.click(screen.getByRole("button", { name: "Go back" }));

    await screen.findByTestId("seats-state");
    expect(cancelOrder).toHaveBeenCalledWith("o-1");
    expect(await seatsNotice()).toBeUndefined();
  });

  it("releases the seats and goes back when the countdown reaches zero", async () => {
    const now = new Date();
    vi.mocked(getOrder).mockResolvedValue(
      order("HELD_AWAITING_PAYMENT", {
        reservationExpiresAt: new Date(now.getTime() - 1000).toISOString(),
        serverNow: now.toISOString(),
      }),
    );
    renderCheckout();

    expect(await seatsNotice()).toMatch(/ran out/);
    expect(cancelOrder).toHaveBeenCalledWith("o-1");
    expect(submitPayment).not.toHaveBeenCalled();
  });

  it("resumes an order opened without the seat page's state", async () => {
    vi.mocked(getOrder).mockResolvedValue(order("HELD_AWAITING_PAYMENT"));
    vi.mocked(getRoute).mockResolvedValue({
      routeId: "R1", origin: "Torino", destination: "Milano", departureTime: "09:00:00",
      arrivalTime: "10:00:00", trainType: "Regional", operatingDays: [], travelTime: "PT1H",
    });
    vi.mocked(listOffers).mockResolvedValue([
      { offerId: "of-001", routeId: "R1", fareClass: "Standard", price: 24.9, currency: "EUR", conditions: "" },
    ]);
    renderCheckout(null);

    expect(await screen.findByRole("button", { name: "Pay now" })).toBeInTheDocument();
    expect(await screen.findByText(/Torino/)).toBeInTheDocument();
  });
});
