import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Same-origin dev proxy: the SPA calls /api/* which Vite forwards to Traefik (port 80),
// mirroring the production same-origin setup (no CORS wildcard needed — 07b slide 12).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:80",
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: "./src/test-setup.ts",
  },
});
