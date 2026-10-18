import { useCountdown } from "../../hooks/useCountdown";

const LATE_SECONDS = 120;

function secondsLeft(label: string | null): number {
  if (!label) return Number.POSITIVE_INFINITY;
  const [m, s] = label.split(":");
  return Number(m) * 60 + Number(s);
}

export interface HoldClockProps {
  expiresAt: string | null;
  /** `serverNow - Date.now()`, so a browser with a skewed clock shows the server's time. */
  clockOffsetMs?: number;
  /** Live only while the order actually holds seats. */
  active: boolean;
}

/** The deadline as a bar across the page it constrains: what is held, and what happens at zero. */
export function HoldBar({
  expiresAt,
  clockOffsetMs = 0,
  active,
  seats,
}: HoldClockProps & { seats: string[] }) {
  const countdown = useCountdown(expiresAt, active, clockOffsetMs);
  if (!countdown.label) return null;

  const seatList = seats.length === 1 ? `Seat ${seats[0]}` : `Seats ${seats.join(", ")}`;
  return (
    <div className="et-hold mb-4" role="timer" aria-live="off">
      <span className="et-hold__clock">
        <i className="bi bi-clock-history" aria-hidden="true" />
        <span className="et-hold__n">{countdown.label}</span>
      </span>
      <span className="et-hold__tx">
        <b>
          {seatList} {seats.length === 1 ? "is" : "are"} held for you.
        </b>
        Pay within the time shown. At zero the seat goes back on sale, the order cancels itself, and
        you are not charged.
      </span>
    </div>
  );
}

/** The same clock where it only has to help pick a row. */
export function HoldPill({ expiresAt, clockOffsetMs = 0, active }: HoldClockProps) {
  const countdown = useCountdown(expiresAt, active, clockOffsetMs);
  if (!countdown.label || countdown.expired) return null;
  const late = secondsLeft(countdown.label) <= LATE_SECONDS;
  return (
    <span className={`et-hold-pill${late ? " et-hold-pill--late" : ""}`}>
      <i className="bi bi-clock-history" aria-hidden="true" />
      {countdown.label}
    </span>
  );
}
