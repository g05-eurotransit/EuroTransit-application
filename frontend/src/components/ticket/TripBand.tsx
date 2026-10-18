import { arrivesNextDay, durationBetween, formatDuration, hhmm } from "../../utils/format";

export interface TripBandProps {
  origin: string;
  destination: string;
  departureTime: string;
  arrivalTime: string;
  service?: string;
  label?: string;
  travelTime?: string;
  variant?: "full" | "slim";
  dead?: boolean;
}

/** The one block of solid colour in the app: the same train, shown the same way on every screen. */
export function TripBand({
  origin,
  destination,
  departureTime,
  arrivalTime,
  service,
  label,
  travelTime,
  variant = "full",
  dead = false,
}: TripBandProps) {
  const slim = variant === "slim";
  const duration = travelTime
    ? formatDuration(travelTime)
    : durationBetween(departureTime, arrivalTime);
  const nextDay = arrivesNextDay(departureTime, arrivalTime);

  return (
    <div className={`et-band${slim ? " et-band--slim" : ""}${dead ? " et-band--dead" : ""}`}>
      <div className="et-band__svc">
        {!label && <i className="bi bi-train-front" aria-hidden="true" />}
        {label ?? service}
      </div>
      <div className={`et-band__od${slim ? " et-band__od--stacked" : ""}`}>
        <div>
          <div className="et-band__t">{hhmm(departureTime)}</div>
          <div className="et-band__s">{origin}</div>
        </div>
        {!slim && (
          <div className="et-band__mid" aria-hidden="true">
            {duration && <span className="et-band__dur">{duration}</span>}
            <span className="et-band__wire" />
          </div>
        )}
        <div className="et-band__end">
          <div className="et-band__t">{hhmm(arrivalTime)}</div>
          <div className="et-band__s">
            {destination}
            {slim && nextDay && " · +1 day"}
          </div>
          {!slim && nextDay && <span className="et-band__plus">+1 day</span>}
        </div>
      </div>
    </div>
  );
}
