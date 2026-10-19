// All API calls are intercepted; no business writes reach a real backend.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE ?? "playwright");
const origin = process.env.FRONTEND_QA_URL ?? "http://localhost:3000";
const id = (n) => `00000000-0000-4000-8000-${String(n).padStart(12,"0")}`;
const facility = {id:id(1),name:"Cơ sở demo",addressLine:"12 Đường Demo",wardCode:"00166",ward:"Cầu Giấy",provinceCode:"01",province:"Hà Nội",timezone:"Asia/Ho_Chi_Minh",latitude:null,longitude:null,amenities:[],status:"ACTIVE",description:null};
const date="2026-10-13",sport=id(90);
const slots=[0,1].map((n) => ({startsAt:`${date}T0${n+1}:00:00Z`,endsAt:`${date}T0${n+2}:00:00Z`,state:"AVAILABLE",amount:100000,currency:"VND",reason:null}));
const courts=[2,3,4].map((n) => ({courtId:id(n),courtName:`Sân ${n-1}`,sportCategoryId:sport,sportName:"Pickleball",fromPrice:100000,currency:"VND",availableSlots:2,slots,firstStartsAt:slots[0].startsAt,firstEndsAt:slots[0].endsAt}));
const item={facility,imageUrl:null,date,matchingCourts:3,sports:[{id:sport,name:"Pickleball"}],fromPrice:100000,currency:"VND",availableSlots:6,distanceKm:null,courts};
const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHANNEL?{channel:process.env.PLAYWRIGHT_CHANNEL}:{})});
try {
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],unexpected=[],requests=[];
 page.on("pageerror",(e)=>errors.push(e.message));
 await page.route("**/api/v1/**", async(route)=>{
  const req=route.request(),url=new URL(req.url()),path=url.pathname.replace("/api/v1","");requests.push({path,query:url.search,method:req.method()});
  const send=(data,status=200)=>route.fulfill({status,contentType:"application/json",body:JSON.stringify({data,message:"OK"})});
  if(path==="/auth/refresh")return send(null,401);
  if(path==="/sport-categories")return send([{id:sport,name:"Pickleball",active:true}]);
  if(path==="/bookings/search/facilities") {
   if(url.searchParams.has("facilityId"))return send({items:[item],page:1,size:1,totalFacilities:1,totalPages:1});
   const current=Number(url.searchParams.get("page")??1);
   const items=current===2?[{...item,facility:{...facility,id:id(30),name:"Cơ sở cuối"}}]:Array.from({length:12},(_,n)=>n===0?item:{...item,facility:{...facility,id:id(10+n),name:`Cơ sở ${n+1}`}});
   return send({items,page:current,size:12,totalFacilities:13,totalPages:2});
  }
  if(path===`/facilities/${facility.id}`)return send(facility);
  if(path===`/facilities/${facility.id}/images`)return send([]);
  if(path===`/facilities/courts/${courts[0].courtId}/context`)return send({facility,court:{id:courts[0].courtId,name:courts[0].courtName}});
  if(path==="/bookings/availability")return send({facilityId:facility.id,courtId:courts[0].courtId,date,timezone:facility.timezone,slots});
  if(path==="/schedules/public/quote")return send({courtId:courts[0].courtId,startsAt:slots[0].startsAt,endsAt:slots[1].endsAt,amount:200000,currency:"VND",segments:slots});
  unexpected.push(path);return send(null,500);
 });
 const query=new URLSearchParams({date,sportCategoryId:sport,opensAfter:"08:00",closesBefore:"10:00",provinceCode:"01",wardCode:"00166",minPrice:"70000",maxPrice:"200000"});
 await page.goto(origin+"/search?"+query);await page.locator(".venue-card").first().waitFor();assert.equal(await page.locator(".venue-card").count(),12);
 assert.equal(await page.getByText("13 cơ sở phù hợp").count(),1);assert.match(await page.locator(".venue-card").first().innerText(),/3 sân phù hợp/);
 assert.equal(new Set(await page.locator(".venue-card").evaluateAll((cards)=>cards.map((c)=>c.href))).size,12);
 await page.getByRole("button",{name:"Trang sau",exact:true}).click();await page.waitForURL(/page=2/);await page.getByRole("heading",{name:"Cơ sở cuối"}).waitFor();assert.equal(await page.locator(".venue-card").count(),1);
 await page.getByRole("button",{name:"Trang trước",exact:true}).click();await page.waitForURL(/page=1/);await page.getByRole("heading",{name:"Cơ sở demo",exact:true}).waitFor();
 await page.getByRole("button",{name:"Tìm sân",exact:true}).click();await page.waitForTimeout(300);assert.equal(new URL(page.url()).searchParams.has("page"),false);
 await page.locator(".venue-card").first().click();await page.waitForURL(u=>u.pathname===`/facilities/${facility.id}`);
 for(const key of query.keys())assert.equal(new URL(page.url()).searchParams.get(key),query.get(key));
 await page.locator(".court-row").first().waitFor();assert.equal(await page.locator(".court-row").count(),3);
 await page.getByRole("link",{name:"Chọn sân và giờ chơi"}).first().click();await page.waitForURL(u=>u.pathname.endsWith(courts[0].courtId));
 assert.equal(new URL(page.url()).searchParams.get("date"),date);assert.equal(new URL(page.url()).searchParams.get("opensAfter"),"08:00");
 await page.locator('.slot[aria-pressed="true"]').first().waitFor();assert.equal(await page.locator('.slot[aria-pressed="true"]').count(),2);
 assert(requests.some((r)=>r.path==="/schedules/public/quote"));assert(requests.filter((r)=>r.path==="/bookings/search/facilities").every((r)=>new URLSearchParams(r.query).get("wardCode")==="00166"));
 await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);console.log(JSON.stringify({status:"PASS",uniqueFacilityCards:true,paginationByFacility:true,filtersPreserved:true,matchingCourts:3,consecutiveSlots:2}));
} finally {await browser.close();}
