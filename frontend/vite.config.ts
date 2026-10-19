// From vitest/config, not vite: it is the same function plus the `test` block below.
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Same-origin dev proxy: the SPA calls /api/* which Vite forwards to Traefik (port 80),
// mirroring the production same-origin setup (no CORS wildcard needed — 07b slide 12).
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      // Two entry points: the application, and the tiny page the silent sign-in iframe loads.
      // Relative to this folder, which is where the build runs from (see frontend/Dockerfile).
      input: {
        main: "index.html",
        "silent-renew": "silent-renew.html",
      },
    },
  },
  css: {
    preprocessorOptions: {
      scss: {
        // Bootstrap 5.3's own SCSS still uses the legacy @import + global color-function API
        // internally (its docs still recommend @import for variable-override theming, which
        // custom-bootstrap.scss follows) — these deprecations are in Bootstrap's source, not
        // ours, and will go away on their own once Bootstrap ships a @use-based rewrite.
        silenceDeprecations: ["import", "if-function", "global-builtin", "color-functions"],
      },
    },
  },
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
