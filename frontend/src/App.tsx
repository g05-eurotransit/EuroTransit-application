import { Routes, Route } from "react-router-dom";
import { NavBar } from "./components/layout/NavBar";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { CallbackPage } from "./auth/CallbackPage";
import { BrowsePage } from "./pages/BrowsePage";
import { SeatSelectionPage } from "./pages/SeatSelectionPage";
import { CheckoutPage } from "./pages/CheckoutPage";
import { OrderStatusPage } from "./pages/OrderStatusPage";
import { NotFoundPage } from "./pages/NotFoundPage";

export function App() {
  return (
    <>
      <NavBar />
      <main className="container-fluid px-4 py-4">
        <Routes>
          <Route path="/" element={<BrowsePage />} />
          <Route path="/callback" element={<CallbackPage />} />
          <Route
            path="/checkout/seats"
            element={
              <ProtectedRoute>
                <SeatSelectionPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/checkout"
            element={
              <ProtectedRoute>
                <CheckoutPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/orders/:orderId"
            element={
              <ProtectedRoute>
                <OrderStatusPage />
              </ProtectedRoute>
            }
          />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </main>
    </>
  );
}
