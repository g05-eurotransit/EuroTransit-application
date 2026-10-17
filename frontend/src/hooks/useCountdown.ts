import { useEffect, useState } from "react";

export interface Countdown {
  /** `m:ss`, or null while there is nothing to count down. */
  label: string | null;
  /** True once the deadline has passed by the corrected clock. */
  expired: boolean;
}

/**
 * Counts down to a server deadline.
 *
 * [clockOffsetMs] is `serverNow - Date.now()` measured when the order was read, so a browser whose
 * clock is minutes off still shows the time the server will actually allow. Presentation only: the
 * server decides whether a payment is still accepted, never this hook.
 */
export function useCountdown(expiresAt: string | null, active: boolean, clockOffsetMs = 0): Countdown {
  const [state, setState] = useState<Countdown>({ label: null, expired: false });

  useEffect(() => {
    if (!expiresAt || !active) {
      setState({ label: null, expired: false });
      return;
    }
    const deadline = new Date(expiresAt).getTime();

    function tick() {
      const remainingMs = deadline - (Date.now() + clockOffsetMs);
      const secondsLeft = Math.max(0, Math.floor(remainingMs / 1000));
      const m = Math.floor(secondsLeft / 60);
      const s = secondsLeft % 60;
      setState({ label: `${m}:${s.toString().padStart(2, "0")}`, expired: remainingMs <= 0 });
    }
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [expiresAt, active, clockOffsetMs]);

  return state;
}

/** `serverNow - Date.now()` for one response, or 0 when the server did not say. */
export function clockOffsetFrom(serverNow: string | null | undefined): number {
  if (!serverNow) return 0;
  const server = new Date(serverNow).getTime();
  return Number.isNaN(server) ? 0 : server - Date.now();
}
