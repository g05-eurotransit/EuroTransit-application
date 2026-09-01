import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { AuthProvider } from "react-oidc-context";
import { createOidcConfig } from "./auth/oidcConfig";
import { loadRuntimeConfig } from "./config/runtimeConfig";
import { TokenSync } from "./auth/TokenSync";
import { SilentSignInProvider } from "./auth/SilentSignIn";
import { NotificationsProvider } from "./notifications/NotificationsProvider";
import { App } from "./App";
import "./custom-bootstrap.scss";
import "bootstrap-icons/font/bootstrap-icons.css";

const root = createRoot(document.getElementById("root")!);

async function bootstrap() {
  try {
    const config = await loadRuntimeConfig();
    const oidcConfig = createOidcConfig(config);

    root.render(
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
  } catch (error) {
    console.error("Frontend runtime configuration failed to load", error);
    root.render(
      <main className="container py-5" role="alert">
        <h1>EuroTransit is temporarily unavailable</h1>
        <p>The application configuration could not be loaded. Please try again later.</p>
      </main>,
    );
  }
}

void bootstrap();
