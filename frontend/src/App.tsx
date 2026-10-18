import { Routes, Route, Navigate } from "react-router-dom";
import { NavBar } from "./components/layout/NavBar";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { useIsOperator } from "./auth/roles";
import { CallbackPage } from "./auth/CallbackPage";
import { BrowsePage } from "./pages/BrowsePage";
import { SeatSelectionPage } from "./pages/SeatSelectionPage";
import { CheckoutPage } from "./pages/CheckoutPage";
import { OrderStatusPage } from "./pages/OrderStatusPage";
import { OrdersListPage } from "./pages/OrdersListPage";
import { OperationsPage } from "./pages/OperationsPage";
import { NotFoundPage } from "./pages/NotFoundPage";

/**
 * Two shells on one role. An operations account cannot place an order and owns no tickets, so every
 * path but the console redirects to it — which also makes the console its landing page.
 * `/operations` is in both shells, so opening it signed out goes through login and back.
 */
export function App() {
  const isOperator = useIsOperator();

  return (
    <>
      <NavBar />
      <main className="container-xxl px-4 py-4">
        <Routes>
          <Route path="/callback" element={<CallbackPage />} />
          <Route
            path="/operations"
            element={
              <ProtectedRoute requireOperator>
                <OperationsPage />
              </ProtectedRoute>
            }
          />
          {/* Shared by both shells: an operator reaches it from the console to refund an order on a
              customer's behalf, and the API answers 404 to a customer who is not the owner. */}
          <Route
            path="/orders/:orderId"
            element={
              <ProtectedRoute>
                <OrderStatusPage />
              </ProtectedRoute>
            }
          />
          {isOperator ? (
            <Route path="*" element={<Navigate to="/operations" replace />} />
          ) : (
            <>
              <Route path="/" element={<BrowsePage />} />
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
                path="/orders"
                element={
                  <ProtectedRoute>
                    <OrdersListPage />
                  </ProtectedRoute>
                }
              />
              <Route path="*" element={<NotFoundPage />} />
            </>
          )}
        </Routes>
      </main>
    </>
  );
}
