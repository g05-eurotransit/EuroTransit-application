import { describe, it, expect } from "vitest";
import { formatTrip } from "../notifications/NotificationsProvider";
import type { NotificationRoute } from "../api/notifications";

const route: NotificationRoute = {
  routeId: "rt-to-mi-001",
  origin: "Torino Porta Nuova",
  destination: "Milano Centrale",
  travelDate: "2026-08-18",
  departureTime: "07:00:00",
};

describe("formatTrip", () => {
  it("names both stations and the departure", () => {
    const line = formatTrip(route);

    expect(line).toContain("Torino Porta Nuova");
    expect(line).toContain("Milano Centrale");
    expect(line).toContain("18");
  });

  it("prints the departure time as it is, without converting it", () => {
    // The value is already local to the departure station. Rendering it in the reader's zone
    // would show a traveller a time that is not the one on their ticket.
    expect(formatTrip(route)).toContain("07:00");
  });

  it("keeps a night train on its departure day", () => {
    // rt-ro-vi-004 leaves at 19:20 and arrives the next morning. Only the departure is shown, so
    // the date must stay the day the customer boards.
    const nightTrain: NotificationRoute = {
      ...route,
      origin: "Roma Termini",
      destination: "Wien Hbf",
      departureTime: "19:20:00",
    };

    const line = formatTrip(nightTrain);
    expect(line).toContain("19:20");
    expect(line).toContain("18");
  });
});
