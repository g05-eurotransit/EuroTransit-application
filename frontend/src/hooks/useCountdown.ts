import { useEffect, useState } from "react";

export function useCountdown(expiresAt: string | null, active: boolean): string | null {
  const [label, setLabel] = useState<string | null>(null);

  useEffect(() => {
    if (!expiresAt || !active) {
      setLabel(null);
      return;
    }
    const deadline = new Date(expiresAt).getTime();

    function tick() {
      const secondsLeft = Math.max(0, Math.floor((deadline - Date.now()) / 1000));
      const m = Math.floor(secondsLeft / 60);
      const s = secondsLeft % 60;
      setLabel(`${m}:${s.toString().padStart(2, "0")}`);
    }
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [expiresAt, active]);

  return label;
}
