import type { FacilityDiscoveryItem, MatchingCourt } from "./domain";
const filters = ["q", "provinceCode", "wardCode", "sportCategoryId", "date", "opensAfter", "closesBefore", "minPrice", "maxPrice", "latitude", "longitude", "radiusKm", "sort"];
export function discoveryQuery(source: URLSearchParams, date?: string) {
  const result = new URLSearchParams();
  for (const key of filters) { const value = source.get(key); if (value) result.set(key, value); }
  if (date) result.set("date", date);
  return result;
}
export function facilitySearchHref(item: FacilityDiscoveryItem, query: URLSearchParams) {
  return `/facilities/${item.facility.id}?${discoveryQuery(query, item.date)}`;
}
export function courtSearchHref(facilityId: string, court: MatchingCourt, query: URLSearchParams) {
  const params = discoveryQuery(query);
  if (params.has("opensAfter") && params.has("closesBefore") && court.slots.length) {
    params.set("start", court.slots[0].startsAt);
    params.set("end", court.slots[court.slots.length - 1].endsAt);
  }
  return `/facilities/${facilityId}/courts/${court.courtId}?${params}`;
}
