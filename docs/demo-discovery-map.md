# Discovery map demo

The list and map use the same real `/api/v1/bookings/search` response. `view=map` is a frontend presentation option; it is not sent as an API filter. Existing date, location, sport, price, time and distance filters remain in the URL. Court links retain the selected date.

## Run and inspect

Start the isolated demo using the commands in `demo-owner-onboarding.md`, then open `http://localhost:13000/search`. Select a date with available courts and switch to **Bản đồ**. The approved facility must have stored coordinates and enabled courts with operating hours/pricing for that date. Demo Center currently has no coordinates and exercises the missing-location fallback. To show tiles, use a facility configured through the real Owner onboarding/workspace flow.

- One marker represents one facility. Its number is the number of matching courts, not a count of free time slots.
- Select a marker or the corresponding facility button to see the real courts, current search price and available-slot counts. **Chọn giờ chơi** opens that court's real availability for the selected date.
- Markers and map controls are keyboard accessible. The facility selector and court links provide a text alternative to the map.
- Courts without valid coordinates remain in the list. The map reports their omission; it does not fabricate a location.
- Empty searches retain the existing empty-state message. Failed base tiles show a warning while facility selection and booking links remain available. **Tải lại bản đồ** recreates the map.
- Leaflet and its CSS load only when viewing map results. Returning to **Danh sách** keeps the filters and removes the map.

## Tile service

Leaflet 1.9.4 is pinned. The demo requests visible raster tiles directly from `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, retains browser caching and displays OpenStreetMap attribution. No API key or user geolocation is required to show public facility coordinates. Tile requests use an origin-only referrer so search parameters and the user's distance-filter coordinates are not shared through the Referer header. There is no prefetch, offline download or tile proxy.

The community service provides best-effort availability. Select a suitable tile provider before a larger production deployment; the list remains usable without base tiles. References: [Leaflet API](https://leafletjs.com/reference.html), [accessible markers](https://leafletjs.com/examples/accessibility/), [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/).

Backend discovery regression: `./scripts/demo/smoke-discovery.ps1`. Frontend verification: run `pnpm lint`, `pnpm typecheck` and `pnpm build` from `frontend/`.

## Recorded browser verification

On 2026-10-06 the approved synthetic `Browser first facility` (coordinates entered during the real onboarding form) temporarily enabled two controlled courts for the date 2026-10-06. The API returned two matching courts with 100,000 VND and 16 slots each. The map displayed one correctly labelled, keyboard-operable marker for both courts, 15 successfully loaded base tiles and visible attribution. The court link opened real availability with the same date. List/map switching retained the filters; setting maximum price to 1 returned the real empty state. At 390 px the document width was 375 px. No browser console errors were recorded. Both fixture courts were disabled again afterward; their data was preserved.

Ignored local evidence: `tmp/demo/discovery-map.jpg` and `tmp/demo/discovery-map-mobile.jpg`. The tile-load failure warning is implemented but was not exercised with a blocked external service.
