import { useEffect, useState } from "react";
import { DAY_LABELS, DAY_MS, MONTHS_SHORT, addDays, isPast, isSameDay, startOfDay } from "../../utils/dates";

interface DateStripProps {
  selected: Date;
  onSelect: (date: Date) => void;
  visibleDays?: number;
}

/** A week of departure days, moved a day at a time — the long jump belongs to the calendar. */
export function DateStrip({ selected, onSelect, visibleDays = 7 }: DateStripProps) {
  const [windowStart, setWindowStart] = useState(() =>
    addDays(selected, -Math.floor(visibleDays / 2)),
  );

  // Only a date chosen elsewhere moves the window: the arrows must not snap back to the selection.
  useEffect(() => {
    const offset = Math.round(
      (startOfDay(selected).getTime() - startOfDay(windowStart).getTime()) / DAY_MS,
    );
    if (offset < 0 || offset >= visibleDays) {
      setWindowStart(addDays(selected, -Math.floor(visibleDays / 2)));
    }
  }, [selected, visibleDays, windowStart]);

  const days = Array.from({ length: visibleDays }, (_, i) => addDays(windowStart, i));

  return (
    <div className="d-flex align-items-center gap-2 bg-white rounded-3 border p-2 mb-4">
      <button
        type="button"
        className="btn btn-primary rounded-circle d-flex align-items-center justify-content-center flex-shrink-0 p-0"
        style={{ width: 40, height: 40 }}
        onClick={() => setWindowStart((d) => addDays(d, -1))}
        aria-label="Previous days"
      >
        <i className="bi bi-arrow-left" aria-hidden="true" />
      </button>

      <div
        className="d-flex flex-grow-1 justify-content-between gap-2 pb-1"
        style={{ overflowX: "auto" }}
      >
        {days.map((day) => {
          const active = isSameDay(day, selected);
          const disabled = isPast(day);
          return (
            <button
              key={day.toISOString()}
              type="button"
              disabled={disabled}
              onClick={() => onSelect(day)}
              className={`btn border-0 rounded-3 px-3 py-2 text-center flex-shrink-0 ${
                active
                  ? "bg-primary text-white"
                  : disabled
                    ? "bg-white text-body-tertiary"
                    : "bg-white text-body"
              }`}
              style={{ minWidth: 76 }}
            >
              <div
                className={`small text-uppercase ${active ? "text-white-50" : "text-secondary-emphasis"}`}
              >
                {DAY_LABELS[day.getDay()]}
              </div>
              <div className="fs-4 fw-bold lh-sm">{day.getDate()}</div>
              <div
                className={`small text-uppercase ${active ? "text-white-50" : "text-secondary-emphasis"}`}
              >
                {MONTHS_SHORT[day.getMonth()]}
              </div>
            </button>
          );
        })}
      </div>

      <button
        type="button"
        className="btn btn-primary rounded-circle d-flex align-items-center justify-content-center flex-shrink-0 p-0"
        style={{ width: 40, height: 40 }}
        onClick={() => setWindowStart((d) => addDays(d, 1))}
        aria-label="Next days"
      >
        <i className="bi bi-arrow-right" aria-hidden="true" />
      </button>
    </div>
  );
}
