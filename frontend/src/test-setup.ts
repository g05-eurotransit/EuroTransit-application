import "@testing-library/jest-dom/vitest";
import { initializeRuntimeConfig } from "./config/runtimeConfig";

initializeRuntimeConfig({
  keycloakIssuer: "http://localhost:8081/realms/eurotransit",
  keycloakClientId: "eurotransit-frontend",
  apiBase: "/api",
  stripePublishableKey: "pk_test_abc123",
});
