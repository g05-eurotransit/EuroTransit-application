import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getRuntimeConfig,
  initializeRuntimeConfig,
  loadRuntimeConfig,
} from "../config/runtimeConfig";

const validConfig = {
  keycloakIssuer: "https://g05.cpo2026.it/realms/eurotransit",
  keycloakClientId: "eurotransit-frontend",
  apiBase: "/api",
};

describe("runtime frontend configuration", () => {
  afterEach(() => vi.restoreAllMocks());

  it("loads, validates and installs the same-origin runtime configuration", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => validConfig,
    } as Response);
    vi.stubGlobal("fetch", fetchMock);

    await expect(loadRuntimeConfig()).resolves.toEqual(validConfig);
    expect(getRuntimeConfig()).toEqual(validConfig);
    expect(fetchMock).toHaveBeenCalledWith("/config/config.json", {
      cache: "no-store",
      headers: { Accept: "application/json" },
    });
  });

  it.each([
    [{ ...validConfig, keycloakIssuer: "http://attacker.example/realms/eurotransit" }],
    [{ ...validConfig, keycloakClientId: "invalid client id" }],
    [{ ...validConfig, apiBase: "https://attacker.example/api" }],
    [{ ...validConfig, apiBase: "//attacker.example/api" }],
  ])("rejects unsafe configuration %#", (config) => {
    expect(() => initializeRuntimeConfig(config)).toThrow(/runtime configuration/i);
  });

  it("fails closed when the configuration file is unavailable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 404 } as Response));

    await expect(loadRuntimeConfig()).rejects.toThrow(
      "Unable to load frontend runtime configuration (404)",
    );
  });
});
