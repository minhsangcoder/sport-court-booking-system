import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import ts from "typescript";
import { webcrypto } from "node:crypto";
import { fileURLToPath } from "node:url";
const __dirname = path.dirname(fileURLToPath(import.meta.url));
function load(file, extra = {}) {
  const exports = {};
  const context = vm.createContext({
    exports,
    module: { exports },
    console,
    URL,
    URLSearchParams,
    Headers,
    Response,
    Request,
    FormData,
    Blob,
    Buffer,
    Uint8Array,
    AbortSignal,
    crypto: webcrypto,
    process: { env: {} },
    fetch: async () => {
      throw Error("Unexpected network request");
    },
    ...extra,
  });
  const source = fs.readFileSync(path.join(__dirname, "..", file), "utf8");
  vm.runInContext(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText,
    context,
  );
  return exports;
}
const selection = load("src/lib/selection.ts");
const routes = load("src/lib/routes.ts");
const domain = load("src/lib/domain.ts");
test("labels preserve arbitrary user strings and money preserves fractional wire values", () => {
  assert.equal(domain.label("constructor"), "constructor");
  assert.equal(domain.label("__proto__"), "__proto__");
  assert.equal(domain.label("PAST_SLOT"), "Đã qua giờ");
  assert.match(domain.money(100000.5), /100\.000,5/);
});
const slot = (hour, state = "AVAILABLE", amount = 100000) => ({
  startsAt: `2026-10-13T${hour}:00:00Z`,
  endsAt: `2026-10-13T${String(Number(hour) + 1).padStart(2, "0")}:00:00Z`,
  state,
  amount,
});
test("continuous available slots preserve server interval and accept reverse click order", () => {
  const a = slot("06"),
    b = slot("07");
  const result = selection.selectedInterval([a, b], [b.startsAt, a.startsAt]);
  assert.equal(result.startsAt, a.startsAt);
  assert.equal(result.endsAt, b.endsAt);
});
test("selection rejects gaps, unavailable states, absent prices and stale ids", () => {
  for (const state of ["BOOKED", "HELD", "BLOCKED", "MAINTENANCE"]) {
    const a = slot("06", state);
    assert.equal(selection.selectedInterval([a], [a.startsAt]), null);
  }
  const a = slot("06"),
    c = slot("08");
  assert.equal(
    selection.selectedInterval([a, c], [a.startsAt, c.startsAt]),
    null,
  );
  assert.equal(
    selection.selectedInterval([slot("06", "AVAILABLE", null)], [a.startsAt]),
    null,
  );
  assert.equal(selection.selectedInterval([a], [a.startsAt, "missing"]), null);
  assert.equal(selection.selectedInterval([a], [a.startsAt, a.startsAt]), null);
});
test("safe login continuation preserves group codes and prevents external redirects", () => {
  assert.equal(
    selection.safeNext("/customer/groups/join?code=one-time-code"),
    "/customer/groups/join?code=one-time-code",
  );
  for (const value of [
    null,
    "https://example.com",
    "//example.com",
    "/\\example.com",
    "/login?next=/",
    "/register",
    "/verify",
    "/x\r\nLocation:evil",
  ])
    assert.equal(selection.safeNext(value), "/customer/bookings");
});
test("route grammar covers every workspace and rejects accidental or invented pages", () => {
  const id = "11111111-2222-4333-8444-555555555555";
  for (const p of [
    "/",
    "/search",
    `/facilities/${id}/courts/${id}`,
    `/customer/booking/holds/${id}`,
    `/customer/groups/${id}/split`,
    `/customer/transfers/acquisitions/${id}`,
    `/owner/application/${id}/facility/schedule/preview`,
    `/owner/facilities/${id}/maintenance`,
    `/owner/bookings/${id}`,
    `/staff/counter-booking/${id}`,
    `/admin/owner-applications/${id}`,
    "/admin/bookings/reconciliation",
  ])
    assert.equal(routes.isFrontendRoute(p), true, p);
  for (const p of [
    "/fake",
    "/facilities/not-a-uuid",
    "/owner/facilities/evil",
    "/admin/facilities/lock",
    "/api/v1/bookings",
    "/customer/groups/join/extra",
  ])
    assert.equal(routes.isFrontendRoute(p), false, p);
});
const json = (data, status = 200) =>
  Response.json({ data, message: "Success" }, { status });
test("API unwraps real envelopes and preserves bearer auth and HttpOnly credentials", async () => {
  const calls = [];
  const api = load("src/lib/api.ts", {
    fetch: async (url, options) => {
      calls.push({ url, options });
      return url.endsWith("/auth/login")
        ? json({ accessToken: "access", user: { id: "u" } })
        : json({ id: "real-record" });
    },
  });
  await api.authApi.login("user@example.com", "credential");
  const data = await api.api("/bookings");
  assert.equal(data.id, "real-record");
  assert.equal(calls[1].options.headers.get("Authorization"), "Bearer access");
  assert.equal(calls[1].options.credentials, "include");
  assert.equal(calls[1].options.cache, "no-store");
  await api.api("/facilities");
  assert.equal(calls[2].options.headers.has("Authorization"), false);
});
test("simultaneous expired requests use one refresh then retry with the new token", async () => {
  let refreshes = 0,
    retries = 0;
  const api = load("src/lib/api.ts", {
    fetch: async (url, options) => {
      if (url.endsWith("/auth/refresh")) {
        refreshes++;
        await new Promise((r) => setTimeout(r, 15));
        return json({ accessToken: "renewed", user: { id: "u" } });
      }
      if (options.headers.get("Authorization") === "Bearer renewed") {
        retries++;
        return json({ id: url });
      }
      return Response.json({ message: "Expired" }, { status: 401 });
    },
  });
  await Promise.all([api.api("/bookings"), api.api("/groups")]);
  assert.equal(refreshes, 1);
  assert.equal(retries, 2);
});
test("expired refresh stops retries and exposes a real authentication error", async () => {
  let calls = 0;
  const api = load("src/lib/api.ts", {
    fetch: async () => {
      calls++;
      return Response.json(
        { message: "Session expired", code: "UNAUTHORIZED" },
        { status: 401 },
      );
    },
  });
  await assert.rejects(
    api.api("/bookings"),
    (e) => e.status === 401 && e.code === "UNAUTHORIZED",
  );
  assert.equal(calls, 2);
});
test("multipart request lets fetch generate the boundary and retains idempotency", async () => {
  let options;
  const api = load("src/lib/api.ts", {
    fetch: async (_, o) => {
      options = o;
      return json({ id: "document" }, 201);
    },
  });
  const body = new FormData();
  body.set(
    "file",
    new Blob(["file"], { type: "application/pdf" }),
    "legal.pdf",
  );
  await api.api("/owner/facilities/id/documents", {
    method: "POST",
    headers: { "Idempotency-Key": "stable-intent" },
    body,
  });
  assert.equal(options.headers.has("Content-Type"), false);
  assert.equal(options.headers.get("Idempotency-Key"), "stable-intent");
  assert.equal(options.body, body);
});
test("API distinguishes empty deletion, field validation, connectivity and invalid responses", async () => {
  const responses = [
    new Response(null, { status: 204 }),
    Response.json(
      {
        message: "Invalid",
        code: "VALIDATION_ERROR",
        fieldErrors: [{ field: "name", message: "Required" }],
      },
      { status: 400 },
    ),
    new Response("bad gateway", { status: 200 }),
  ];
  const api = load("src/lib/api.ts", { fetch: async () => responses.shift() });
  assert.equal(
    await api.api("/owner/maintenance/id", { method: "DELETE" }),
    undefined,
  );
  await assert.rejects(
    api.api("/owner/facilities", { method: "POST", body: "{}" }),
    (e) => e.status === 400 && e.fieldErrors[0].field === "name",
  );
  await assert.rejects(api.api("/bookings"), (e) => e.status === 502);
  const offline = load("src/lib/api.ts", {
    fetch: async () => {
      throw Error("offline");
    },
  });
  await assert.rejects(offline.api("/bookings"), (e) => e.status === 0);
});
const request = (url, options = {}) =>
  Object.assign(new Request(url, options), { nextUrl: new URL(url) });
const routeContext = {
  params: Promise.resolve({ segments: ["auth", "refresh"] }),
};
test("gateway proxy rejects cross-origin writes before contacting services", async () => {
  let calls = 0;
  const proxy = load("app/api/v1/[...segments]/route.ts", {
    fetch: async () => {
      calls++;
      return json(null);
    },
  });
  for (const headers of [
    { origin: "https://evil.example", host: "localhost:3000" },
    { "sec-fetch-site": "cross-site", host: "localhost:3000" },
    { origin: "null", host: "localhost:3000" },
  ]) {
    const r = await proxy.POST(
      request("http://localhost:3000/api/v1/auth/refresh", {
        method: "POST",
        headers,
      }),
      routeContext,
    );
    assert.equal(r.status, 403);
  }
  assert.equal(calls, 0);
});
test("gateway proxy preserves auth, query, statuses and refresh cookies without forwarding browser Origin", async () => {
  let forwarded;
  const proxy = load("app/api/v1/[...segments]/route.ts", {
    process: { env: { GATEWAY_URL: "http://gateway:8080" } },
    fetch: async (url, options) => {
      forwarded = { url: String(url), options };
      return new Response(null, {
        status: 204,
        headers: {
          "set-cookie":
            "refresh=opaque; HttpOnly; Path=/api/v1/auth; SameSite=Lax",
        },
      });
    },
  });
  const r = await proxy.POST(
    request("http://localhost:3000/api/v1/auth/refresh?device=one", {
      method: "POST",
      headers: {
        host: "localhost:3000",
        origin: "http://localhost:3000",
        cookie: "refresh=old",
        authorization: "Bearer access",
        "Content-Type": "application/json",
        "Idempotency-Key": "one",
      },
      body: "{}",
    }),
    routeContext,
  );
  assert.equal(r.status, 204);
  assert.equal(
    forwarded.url,
    "http://gateway:8080/api/v1/auth/refresh?device=one",
  );
  assert.equal(forwarded.options.headers.get("cookie"), "refresh=old");
  assert.equal(forwarded.options.headers.get("authorization"), "Bearer access");
  assert.equal(forwarded.options.headers.get("Idempotency-Key"), "one");
  assert.equal(forwarded.options.headers.has("origin"), false);
  assert.match(r.headers.get("set-cookie"), /HttpOnly/);
  assert.equal(Buffer.from(forwarded.options.body).toString(), "{}");
});
test("gateway proxy enforces upload size and reports upstream failure", async () => {
  const proxy = load("app/api/v1/[...segments]/route.ts");
  const big = await proxy.POST(
    request("http://localhost:3000/api/v1/auth/register", {
      method: "POST",
      headers: {
        host: "localhost:3000",
        "content-length": String(32 * 1024 * 1024),
      },
      body: "x",
    }),
    routeContext,
  );
  assert.equal(big.status, 413);
  const failed = await proxy.GET(
    request("http://localhost:3000/api/v1/auth/refresh"),
    routeContext,
  );
  assert.equal(failed.status, 502);
});


test("actual client authenticates personal favorites while keeping public discovery anonymous", async () => {
  const calls=[];
  const client=load("lib/api.ts", {fetch:async(url,options)=>{calls.push({url,options});return url.endsWith("/auth/login")?json({accessToken:"favorite-access",user:{id:"u"}}):json({favorite:true});}});
  await client.authApi.login("u@example.com","credential");
  for(const p of ["/facilities/favorites?page=1", "/facilities/11111111-2222-4333-8444-555555555555/favorite"])await client.api(p);
  assert.equal(calls[1].options.headers.get("authorization"),"Bearer favorite-access");
  assert.equal(calls[2].options.headers.get("authorization"),"Bearer favorite-access");
  await client.api("/facilities?provinceCode=01");assert.equal(calls[3].options.headers.has("authorization"),false);await client.api("/facilities/11111111-2222-4333-8444-555555555555/operations/access?permission=SCHEDULE_MANAGE");assert.equal(calls[4].options.headers.get("authorization"),"Bearer favorite-access");
});
test("configuration impact details survive the real client error envelope and accepted void responses", async () => {
  const details={reason:"CONFIGURATION_IMPACT",affectedCount:1,affectedReservations:[{id:"reservation",state:"BOOKED"}],truncated:false};
  const responses=[Response.json({message:"Conflict",code:"COMMON-002",details},{status:409}),Response.json({message:"Accepted"},{status:202})];
  const client=load("lib/api.ts",{fetch:async()=>responses.shift()});
  await assert.rejects(client.api("/owner/courts/id/maintenance",{method:"POST",body:"{}"}),e=>e.status===409&&e.details.affectedCount===1&&e.details.affectedReservations[0].state==="BOOKED");
  assert.equal(await client.api("/groups/id/reminders",{method:"POST"}),undefined);
});
test("current fallback route grammar includes new scoped screens and excludes invented paths",()=>{
  const current=load("lib/routes.ts"),id="11111111-2222-4333-8444-555555555555";
  for(const p of ["/customer/favorites","/customer/staff-invitations",`/customer/groups/play-groups/${id}`,`/owner/facilities/${id}/court-types`,`/owner/application/${id}/facility/court-types`,`/staff/bookings/${id}`,`/staff/facilities/${id}/schedule`,`/staff/facilities/${id}/schedule/preview`,`/admin/bookings/reconciliation/${id}`,"/admin/sports"])assert.equal(current.isFrontendRoute(p),true,p);
  for(const p of ["/admin/execute-refund", "/owner/facilities/invalid/court-types",`/staff/bookings/${id}/extra`])assert.equal(current.isFrontendRoute(p),false,p);
});

const discoveryNavigation = load("src/lib/discovery.ts");
test("facility and court links preserve search filters and omit pagination", () => {
  const query = new URLSearchParams("date=2026-10-13&sportCategoryId=sport&provinceCode=01&wardCode=00166&opensAfter=08%3A00&closesBefore=10%3A00&minPrice=70&maxPrice=200&page=2&size=12");
  const facility = { facility: { id: "facility" }, date: "2026-10-13" };
  const href = new URL(discoveryNavigation.facilitySearchHref(facility, query), "https://sporthub.local");
  assert.equal(href.pathname, "/facilities/facility");
  for (const key of ["date", "sportCategoryId", "provinceCode", "wardCode", "opensAfter", "closesBefore", "minPrice", "maxPrice"]) assert.equal(href.searchParams.get(key), query.get(key));
  assert.equal(href.searchParams.has("page"), false);
  const court = { courtId: "court", slots: [{ startsAt: "2026-10-13T01:00:00Z", endsAt: "2026-10-13T02:00:00Z" }, { startsAt: "2026-10-13T02:00:00Z", endsAt: "2026-10-13T03:00:00Z" }] };
  const next = new URL(discoveryNavigation.courtSearchHref("facility", court, query), "https://sporthub.local");
  assert.equal(next.pathname, "/facilities/facility/courts/court");
  assert.equal(next.searchParams.get("start"), court.slots[0].startsAt);
  assert.equal(next.searchParams.get("end"), court.slots[1].endsAt);
  assert.equal(next.searchParams.get("sportCategoryId"), "sport");
  const unrestricted = new URL(discoveryNavigation.courtSearchHref("facility", court, new URLSearchParams("date=2026-10-14")), "https://sporthub.local");
  assert.equal(unrestricted.searchParams.has("start"), false);
  assert.equal(discoveryNavigation.discoveryQuery(query, "2026-10-14").get("date"), "2026-10-14");
});
