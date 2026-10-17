import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clockOffsetFrom, useCountdown } from "../hooks/useCountdown";

const NOW = new Date("2026-10-17T12:00:00Z").getTime();

describe("useCountdown", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => vi.useRealTimers());

  it("counts down to the deadline and reports when it has passed", () => {
    const { result } = renderHook(() => useCountdown("2026-10-17T12:00:05Z", true));
    expect(result.current).toEqual({ label: "0:05", expired: false });

    act(() => vi.advanceTimersByTime(5000));
    expect(result.current).toEqual({ label: "0:00", expired: true });
  });

  it("trusts the server's clock over a browser clock that is two minutes fast", () => {
    // The browser reads 12:00:00 but the server said 11:58:00: the hold still has 10 minutes, not 8.
    const offset = clockOffsetFrom("2026-10-17T11:58:00Z");
    const { result } = renderHook(() => useCountdown("2026-10-17T12:08:00Z", true, offset));
    expect(result.current.label).toBe("10:00");
  });

  it("shows nothing while inactive", () => {
    const { result } = renderHook(() => useCountdown("2026-10-17T12:08:00Z", false));
    expect(result.current).toEqual({ label: null, expired: false });
  });

  it("treats a missing or unreadable serverNow as no correction", () => {
    expect(clockOffsetFrom(null)).toBe(0);
    expect(clockOffsetFrom("not a date")).toBe(0);
  });
});
