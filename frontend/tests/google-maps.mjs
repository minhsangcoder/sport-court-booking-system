// All application API requests use isolated fixtures. Set GOOGLE_MAPS_LIVE=1
// to also verify the configured key against Google instead of the SDK fixture.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { googleMapsFixture } from "./fixtures/google-maps.mjs";

const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE ?? "playwright");
const origin = process.env.FRONTEND_QA_URL ?? "http://localhost:3000";
const live = process.env.GOOGLE_MAPS_LIVE === "1";
const output = fileURLToPath(new URL("../../tmp/google-maps/", import.meta.url));
const id = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const date = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
const category = { id: id(90), name: "Pickleball", active: true };
const facility = {
  id: id(1), name: "Cơ sở Google Maps", addressLine: "12 Đường Demo",
  province: "Hà Nội", district: "Cầu Giấy", ward: "Dịch Vọng",
  timezone: "Asia/Ho_Chi_Minh", latitude: 21.028, longitude: 105.78,
  amenities: [], status: "ACTIVE", description: null,
};
const item = {
  facility, imageUrl: null, date, matchingCourts: 1,
  sports: [{ id: category.id, name: category.name }],
  fromPrice: 100000, currency: "VND", availableSlots: 2, distanceKm: null, courts: [],
};
const items = [item, { ...item, facility: { ...facility, id: id(2), name: "Cơ sở thứ hai", latitude: 21.04, longitude: 105.8 } }, item,
  { ...item, facility: { ...facility, id: id(3), latitude: null, longitude: null } }];
const browser = await chromium.launch({ headless: true,
  ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}),
});
const errors = [], unexpected = [], legacyTiles = [];
let inspectedPage;
fs.mkdirSync(output, { recursive: true });
async function fixture(context) {
  await context.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url()), p = url.pathname.replace("/api/v1", "");
    const send = (data, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify({ data, message: "Fixture" }) });
    if (p === "/auth/refresh") return send(null, 401);
    if (p === "/sport-categories") return send([category]);
    if (p === "/bookings/search/facilities") return send({ items, page: 1, size: 12, totalFacilities: items.length, totalPages: 1 });
    unexpected.push(`${route.request().method()} ${p}`);
    return send(null, 501);
  });
}
function capture(page) {
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    const code = message.text().match(/Google Maps JavaScript API error: (\w+)/)?.[1];
    if (code) errors.push(code);
    if (/Permission Denied/.test(message.text())) errors.push("Google Maps: Permission Denied");
  });
  page.on("request", (request) => {
    if (request.url().includes("tile.openstreetmap.org")) legacyTiles.push("OpenStreetMap tile");
  });
}
async function openPicker(page) {
  await page.goto(origin + "/register");
  await page.getByRole("checkbox", { name: "Tôi muốn đăng ký trở thành chủ sân" }).check();
  await page.getByRole("button", { name: "Chọn vị trí trên bản đồ", exact: true }).click();
  return page.locator(".map-container");
}
function markerPosition(pin) {
  const point = pin.position;
  return point ? { lat: typeof point.lat === "function" ? point.lat() : point.lat,
    lng: typeof point.lng === "function" ? point.lng() : point.lng } : null;
}
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await fixture(context);
  if (!live) await context.addInitScript(googleMapsFixture);
  const page = await context.newPage();
  inspectedPage = page;
  capture(page);
  const picker = await openPicker(page);
  await picker.locator(".gm-style, .gm-err-container").first().waitFor({ timeout: 30000 });
  assert.equal(await picker.locator(".gm-err-container").count(), 0, "Google refused to load the map; check Maps JavaScript API permissions, billing and referrers");
  assert.equal(await page.locator('[name="latitude"]').inputValue(), "", "Default map center must not select a position");
  assert.equal(await page.locator('[name="longitude"]').inputValue(), "");
  await picker.click({ position: { x: 180, y: 150 } });
  await picker.locator("gmp-advanced-marker").waitFor();
  const selected = await page.locator('[name="latitude"]').inputValue();
  assert(selected && Number.isFinite(Number(selected)));
  assert.equal(await picker.locator("gmp-advanced-marker").count(), 1);
  await page.waitForTimeout(600); // Let the map finish panning before a real pointer drag.
  const pin = picker.locator("gmp-advanced-marker");
  await pin.scrollIntoViewIfNeeded();
  const box = await pin.boundingBox();
  assert(box && box.width > 0 && box.height > 0);
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 65, box.y + box.height / 2 + 35, { steps: 12 });
  await page.mouse.up();
  await page.waitForFunction((previous) => document.querySelector('[name="latitude"]').value !== previous, selected);
  const afterDrag = await page.locator('[name="latitude"]').inputValue();
  await page.getByRole("button", { name: "Ẩn bản đồ", exact: true }).click();
  await page.getByRole("button", { name: "Chọn vị trí trên bản đồ", exact: true }).click();
  await picker.locator("gmp-advanced-marker").waitFor();
  assert.equal(await page.locator('[name="latitude"]').inputValue(), afterDrag);
  assert.equal(await picker.locator("gmp-advanced-marker").count(), 1);
  await picker.screenshot({ path: path.join(output, "location-picker.png") });
  await page.locator('[name="latitude"]').fill("0");
  await page.locator('[name="longitude"]').fill("0");
  await page.waitForFunction(() => {
    const p = document.querySelector("gmp-advanced-marker")?.position;
    return p && (typeof p.lat === "function" ? p.lat() : p.lat) === 0 && (typeof p.lng === "function" ? p.lng() : p.lng) === 0;
  });
  assert.deepEqual(await picker.locator("gmp-advanced-marker").evaluate(markerPosition), { lat: 0, lng: 0 });

  const query = new URLSearchParams({ date, sportCategoryId: category.id, minPrice: "70000" });
  await page.goto(origin + "/search?" + query);
  await page.locator(".venue-card").first().waitFor();
  await page.getByRole("button", { name: "Bản đồ", exact: true }).click();
  const map = page.locator(".map-container");
  await map.locator("gmp-advanced-marker").nth(1).waitFor();
  assert.equal(await map.locator("gmp-advanced-marker").count(), 2, "Exclude duplicate facilities and missing coordinates");
  await map.locator("gmp-advanced-marker").first().click();
  const link = page.getByRole("link", { name: "Xem sân trống →", exact: true });
  await link.waitFor();
  const href = new URL(await link.getAttribute("href"), origin);
  for (const [key, value] of query) assert.equal(href.searchParams.get(key), value);
  assert.equal(href.pathname, `/facilities/${facility.id}`);
  await map.screenshot({ path: path.join(output, "search-map.png") });
  await page.getByRole("button", { name: "Danh sách", exact: true }).click();
  await page.getByRole("button", { name: "Bản đồ", exact: true }).click();
  await map.locator("gmp-advanced-marker").nth(1).waitFor();
  assert.equal(await map.locator("gmp-advanced-marker").count(), 2);
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  assert.equal(await page.locator('script[src*="maps.googleapis.com/maps/api/js"]').count(), live ? 1 : 0);
  assert.equal(await page.locator(".gm-err-container").count(), 0);
  assert.deepEqual(errors, []);
  assert.deepEqual(unexpected, []);
  assert.deepEqual(legacyTiles, []);
  await context.close();

  const offline = await browser.newContext();
  await fixture(offline);
  await offline.route("https://maps.googleapis.com/maps/api/js**", (route) => route.abort());
  const fallback = await offline.newPage();
  await openPicker(fallback);
  await fallback.getByText("Không tải được Google Maps. Bạn vẫn có thể nhập tọa độ trực tiếp.", { exact: true }).waitFor();
  await fallback.locator('[name="latitude"]').fill("21.028");
  assert.equal(await fallback.locator('[name="latitude"]').inputValue(), "21.028");
  await offline.close();
  console.log(JSON.stringify({ status: "PASS", sdk: live ? "Google Maps" : "fixture", pickerClick: true, markerDrag: true, manualCoordinates: true,
    searchMarkers: 2, filtersPreserved: true, reopen: true, mobile: true, networkFailureFallback: true, businessWritesToRealBackend: 0, output }));
} catch (error) {
  if (inspectedPage && !inspectedPage.isClosed()) {
    await inspectedPage.screenshot({ path: path.join(output, "failure.png"), fullPage: true }).catch(() => {});
    console.error(await inspectedPage.locator(".map-container").evaluateAll((nodes) => nodes.map((node) => ({
      label: node.getAttribute("aria-label"), text: node.innerText.slice(0, 500),
      width: node.clientWidth, height: node.clientHeight, maps: node.querySelectorAll(".gm-style").length,
    }))));
  }
  console.error(JSON.stringify({ status: "FAIL", googleErrors: errors, unexpected, legacyTiles }));
  throw error;
} finally {
  await browser.close();
}
