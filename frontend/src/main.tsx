import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { AuthProvider } from "react-oidc-context";
import { oidcConfig } from "./auth/oidcConfig";
import { TokenSync } from "./auth/TokenSync";
import { SilentSignInProvider } from "./auth/SilentSignIn";
import { NotificationsProvider } from "./notifications/NotificationsProvider";
import { App } from "./App";
import "./custom-bootstrap.scss";
import "bootstrap-icons/font/bootstrap-icons.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AuthProvider {...oidcConfig}>
      <TokenSync />
      <SilentSignInProvider>
        <NotificationsProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </NotificationsProvider>
      </SilentSignInProvider>
    </AuthProvider>
  </StrictMode>,
);
