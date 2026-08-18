import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { apiFetch, ApiError, newId } from "../api/client";
import { setAccessToken } from "../auth/tokenHolder";

function mockFetch(status: number, body: unknown, ok = status < 400) {
  return vi.fn().mockResolvedValue({
    ok,
    status,
    statusText: "x",
    json: async () => body,
  } as Response);
}

describe("apiFetch", () => {
  beforeEach(() => setAccessToken(null));
  afterEach(() => vi.restoreAllMocks());

  it("attaches Bearer token, Idempotency-Key and X-Correlation-Id on an authed mutating call", async () => {
    setAccessToken("tok-123");
    const f = mockFetch(200, { orderId: "o1", status: "ACCEPTED" });
    vi.stubGlobal("fetch", f);

    await apiFetch("/orders", {
      method: "POST",
      body: { a: 1 },
      auth: true,
      idempotencyKey: "idem-1",
      correlationId: "corr-1",
    });

    const [, init] = f.mock.calls[0];
    const h = init.headers as Record<string, string>;
    expect(h["Authorization"]).toBe("Bearer tok-123");
    expect(h["Idempotency-Key"]).toBe("idem-1");
    expect(h["X-Correlation-Id"]).toBe("corr-1");
    expect(init.method).toBe("POST");
  });

  it("does NOT attach Authorization when auth is not requested", async () => {
    setAccessToken("tok-123");
    const f = mockFetch(200, []);
    vi.stubGlobal("fetch", f);
    await apiFetch("/catalog/routes");
    const [, init] = f.mock.calls[0];
    expect((init.headers as Record<string, string>)["Authorization"]).toBeUndefined();
  });

  it.each([
    [401, "unauthenticated"],
    [403, "forbidden"],
    [404, "not_found"],
    [409, "conflict"],
    [422, "business"],
    [503, "dependency"],
  ] as const)("maps HTTP %i to error kind %s", async (status, kind) => {
    const f = mockFetch(status, { code: "X", message: "m", correlationId: "c" }, false);
    vi.stubGlobal("fetch", f);
    await expect(apiFetch("/orders", { method: "POST" })).rejects.toMatchObject({
      kind,
      status,
      code: "X",
      correlationId: "c",
    });
  });

  it("wraps a thrown fetch as a network ApiError", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    const err = await apiFetch("/catalog/routes").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).kind).toBe("network");
  });
});

describe("newId", () => {
  it("returns distinct high-entropy ids", () => {
    expect(newId()).not.toBe(newId());
  });
});
