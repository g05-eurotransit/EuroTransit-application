import { useEffect, useState } from "react";
import { Dropdown } from "react-bootstrap";
import {
  MONTHS_LONG,
  MONTHS_SHORT,
  WEEKDAYS_MON_FIRST,
  addMonths,
  daysInMonth,
  formatFullDate,
  isSameDay,
  isSameMonth,
  startOfDay,
  startOfMonth,
} from "../../utils/dates";

/** How far ahead the timetable is sold. */
const MONTHS_AHEAD = 11;

/** Monday = 0, so the first row lines up under the Mo column. */
function leadingBlanks(month: Date): number {
  return (month.getDay() + 6) % 7;
}

export interface MonthPickerProps {
  selected: Date;
  onSelect: (date: Date) => void;
  /** Days no route operates on come back false and are shown closed. */
  isDayAvailable?: (date: Date) => boolean;
}

/** The long jump between dates: the month rail turns forty "next week" clicks into one. */
export function MonthPicker({ selected, onSelect, isDayAvailable }: MonthPickerProps) {
  const [open, setOpen] = useState(false);
  const [month, setMonth] = useState(() => startOfMonth(selected));

  // Reopening shows the month you are actually looking at.
  useEffect(() => {
    if (!open) setMonth(startOfMonth(selected));
  }, [open, selected]);

  const today = startOfDay(new Date());
  const firstMonth = startOfMonth(today);
  const lastMonth = addMonths(firstMonth, MONTHS_AHEAD);
  const rail = Array.from({ length: MONTHS_AHEAD + 1 }, (_, i) => addMonths(firstMonth, i));
  const days = Array.from(
    { length: daysInMonth(month) },
    (_, i) => new Date(month.getFullYear(), month.getMonth(), i + 1),
  );

  function pick(day: Date) {
    setOpen(false);
    onSelect(day);
  }

  return (
    <Dropdown show={open} onToggle={setOpen} autoClose="outside">
      <Dropdown.Toggle as="button" type="button" bsPrefix="et-datebtn" aria-label="Choose a travel date">
        <i className="bi bi-calendar3" aria-hidden="true" />
        {formatFullDate(selected)}
        <i className="bi bi-chevron-down text-secondary-emphasis" aria-hidden="true" />
      </Dropdown.Toggle>

      <Dropdown.Menu className="p-0 border-0 bg-transparent">
        <div className="et-cal">
          <div className="et-cal__head">
            <span>
              {MONTHS_LONG[month.getMonth()]} {month.getFullYear()}
            </span>
            <span className="d-flex gap-1">
              <button
                type="button"
                className="et-cal__arrow"
                aria-label="Previous month"
                disabled={isSameMonth(month, firstMonth)}
                onClick={() => setMonth((m) => addMonths(m, -1))}
              >
                <i className="bi bi-arrow-left" aria-hidden="true" />
              </button>
              <button
                type="button"
                className="et-cal__arrow"
                aria-label="Next month"
                disabled={isSameMonth(month, lastMonth)}
                onClick={() => setMonth((m) => addMonths(m, 1))}
              >
                <i className="bi bi-arrow-right" aria-hidden="true" />
              </button>
            </span>
          </div>

          <div className="et-cal__rail">
            {rail.map((m, i) => (
              <button
                key={`${m.getFullYear()}-${m.getMonth()}`}
                type="button"
                className={`et-cal__month${isSameMonth(m, month) ? " is-on" : ""}`}
                onClick={() => setMonth(m)}
              >
                {MONTHS_SHORT[m.getMonth()]}
                {m.getMonth() === 0 || i === 0 ? ` ${String(m.getFullYear()).slice(2)}` : ""}
              </button>
            ))}
          </div>

          <div className="et-cal__grid">
            {WEEKDAYS_MON_FIRST.map((w) => (
              <span key={w} className="et-cal__wd">
                {w}
              </span>
            ))}
            {Array.from({ length: leadingBlanks(month) }, (_, i) => (
              <span key={`blank-${i}`} />
            ))}
            {days.map((day) => (
              <button
                key={day.toISOString()}
                type="button"
                className={`et-cal__day${isSameDay(day, selected) ? " is-on" : ""}`}
                disabled={day < today || isDayAvailable?.(day) === false}
                onClick={() => pick(day)}
              >
                {day.getDate()}
              </button>
            ))}
          </div>
        </div>
      </Dropdown.Menu>
    </Dropdown>
  );
}
