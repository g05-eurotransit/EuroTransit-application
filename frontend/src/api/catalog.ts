import { apiFetch } from "./client";

// Wire types mirror the Catalog service DTOs (catalog/.../model). Public reads, no auth.
export interface Route {
  routeId: string;
  origin: string;
  destination: string;
  departureTime: string; // ISO LocalTime, e.g. "09:10:00"
  arrivalTime: string;
  trainType: string;
  operatingDays: string[];
  travelTime: string; // ISO-8601 duration, e.g. "PT7H12M"
}

export interface Offer {
  offerId: string;
  routeId: string;
  fareClass: string;
  price: number;
  currency: string;
  conditions: string;
}

export function listRoutes(signal?: AbortSignal): Promise<Route[]> {
  return apiFetch<Route[]>("/catalog/routes", { signal });
}

export function getRoute(routeId: string, signal?: AbortSignal): Promise<Route> {
  return apiFetch<Route>(`/catalog/routes/${encodeURIComponent(routeId)}`, { signal });
}

export function listOffers(signal?: AbortSignal): Promise<Offer[]> {
  return apiFetch<Offer[]>("/catalog/offers", { signal });
}
