/** How prices, references, times and durations are written across the application. */

/** Orders sold before the currency field existed carry none; the catalogue is priced in euro. */
export const CURRENCY = "EUR";

export function money(amount: number, currency: string | null = CURRENCY): string {
  return `${amount.toFixed(2)} ${currency ?? CURRENCY}`;
}

/** The first block of a UUID: short enough to read out, long enough to tell orders apart. */
export function shortRef(id: string): string {
  return id.slice(0, 8);
}

/** An ISO local time ("07:00:00") as it is printed on a ticket. */
export function hhmm(time: string): string {
  return time.slice(0, 5);
}

/** "PT7H12M" → 432; anything unparseable is 0, and the caller falls back to the clock difference. */
export function durationMinutes(iso: string): number {
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?$/.exec(iso);
  if (!match) return 0;
  return Number(match[1] ?? 0) * 60 + Number(match[2] ?? 0);
}

export function formatMinutes(total: number): string {
  const h = Math.floor(total / 60);
  const m = total % 60;
  return m ? `${h}h ${m}m` : `${h}h`;
}

/** "PT7H12M" → "7h 12m". */
export function formatDuration(iso: string): string {
  return formatMinutes(durationMinutes(iso));
}

function minutesOf(time: string): number {
  const [h, m] = time.split(":");
  return Number(h) * 60 + Number(m);
}

/** A journey that lands the day after it leaves: the arrival clock has gone past midnight. */
export function arrivesNextDay(departureTime: string, arrivalTime: string): boolean {
  if (!departureTime || !arrivalTime) return false;
  return minutesOf(arrivalTime) < minutesOf(departureTime);
}

/** The journey time from the two clock times, for when the catalogue duration is not at hand. */
export function durationBetween(departureTime: string, arrivalTime: string): string {
  if (!departureTime || !arrivalTime) return "";
  const raw = minutesOf(arrivalTime) - minutesOf(departureTime);
  return formatMinutes(raw < 0 ? raw + 24 * 60 : raw);
}
