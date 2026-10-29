// Isolated browser fixtures. Real permissions, bindings and DB accounting are covered by backend/E2E suites.
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import fs from 'node:fs'
const require=createRequire(import.meta.url)
const {chromium}=require(process.env.SPORTHUB_E2E_PLAYWRIGHT||'playwright')
const base=process.env.SPORTHUB_UI_URL||'http://127.0.0.1:3017'
assert.match(new URL(base).hostname,/^(localhost|127\.0\.0\.1)$/)
const id=n=>`78000000-0000-0000-0000-${String(n).padStart(12,'0')}`
const fid=id(1),owner=id(2),bid=id(3),court=id(4),acq=id(5),refund=id(6),listing=id(7),payment=id(8)
const facility={id:fid,ownerId:owner,name:'Cụm sân DEMO',status:'ACTIVE',timezone:'Asia/Ho_Chi_Minh',version:0,amenities:[]}
const terms={mode:'DEMO',receiverName:facility.name,receiverFacilityId:fid,receiverOwnerId:owner,amount:'100000',feeBps:500,policyVersion:1,quotedFeeAmount:'5000',quotedSellerNetAmount:'95000',termsHash:'a'.repeat(64),refundPolicy:{rateBps:8000},currency:'VND'}
const now=new Date().toISOString(),future=new Date(Date.now()+86400000).toISOString(),later=new Date(Date.now()+90000000).toISOString()
const settlement={acquisitionId:acq,bookingId:bid,sellerId:id(20),buyerId:id(21),amount:'100000',feeBps:500,feeAmount:'5000',sellerNetAmount:'95000',payoutState:'UNPAID'}
const refundItem={id:refund,bookingId:bid,baseAmount:'100000',rateBps:8000,targetAmount:'80000',reason:'POLICY_CANCELLATION',state:'PENDING'}
const listingItem={id:listing,facilityId:fid,courtId:court,venue:{facilityName:facility.name,courtName:'Sân cầu lông 1',address:'Địa chỉ demo',timezone:facility.timezone,amenities:[]},originalAmount:120000,price:100000,currency:'VND',startsAt:future,endsAt:later,deadline:future,state:'ACTIVE',own:false,available:true,version:0,financeTerms:terms}
const browser=await chromium.launch({headless:true,...(process.env.SPORTHUB_E2E_BROWSER_CHANNEL?{channel:process.env.SPORTHUB_E2E_BROWSER_CHANNEL}:{})})
const contexts=[],errors=[]
async function fixture(role='OWNER',permissions=['BOOKING_READ','BOOKING_REFUND','TRANSFER_PAYOUT']){
 const context=await browser.newContext({locale:'vi-VN',timezoneId:'America/Los_Angeles',viewport:{width:1440,height:1000}});contexts.push(context)
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message))
 const state={queries:[],mutations:[],payout:'UNPAID',refund:'PENDING',availability:'COMPLETE',fail:false,empty:false,delayDate:'',revoke:false,acquisition:'PENDING',paid:false,seller:false}
 const actor={id:role==='OWNER'?owner:id(21),roles:[role],fullName:'Người dùng DEMO',email:'fixture@example.invalid',status:'ACTIVE',emailVerified:true}
 await context.route('**/api/v1/**',async route=>{
  const request=route.request(),url=new URL(request.url()),path=url.pathname.replace('/api/v1',''),method=request.method()
  const respond=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({status,message:status===403?'Quyền thực hiện đã bị thu hồi.':'Fixture response',data})})
  if(path==='/auth/refresh')return respond({accessToken:'fixture-token',tokenType:'Bearer',expiresIn:300,user:actor})
  if(path==='/owner/facilities')return respond([facility])
  if(path==='/facilities')return respond([facility])
  if(path===`/facilities/${fid}`||path===`/owner/facilities/${fid}`)return respond(facility)
  if(path==='/users/me/access')return respond({facilityBindings:[{facilityId:fid,permissions}]})
  if(path==='/bookings/management/search')return respond({items:[],total:0,totalPages:0})
  if(path.includes('/dashboard')){
   state.queries.push(url.searchParams.toString());const fromDate=url.searchParams.get('fromDate'),toDate=url.searchParams.get('toDate'),p=Number(url.searchParams.get('page'))
   if(fromDate===state.delayDate)await new Promise(resolve=>setTimeout(resolve,550))
   if(state.fail)return respond(null,503)
   const m={courtCollected:state.empty?'0':'120000',transferFees:state.empty?'0':'5000',operatingRevenue:state.empty?'0':'125000',policyRefunded:state.empty?'0':'80000',afterRefund:state.empty?'0':'45000',transferCollected:'100000',sellerPaid:'0',sellerDue:'95000',refundDue:'0',technicalRefunded:'0'}
   const summary={bookingCount:2,fullyPaidBookingCount:2,bookedMinutes:'120',usedMinutes:'60',cancelledCount:1,expiredCount:0,money:m}
   return respond({facilityId:fid,mode:'DEMO',label:'DEMO',asOf:now,obligationCutoff:now,window:{fromDate,toDate,from:now,to:now,timezone:facility.timezone,interval:'[from,to)'},moneyAvailability:state.availability,reasons:state.availability==='COMPLETE'?[]:['PAYMENT_SOURCE_TIMEOUT'],summary,daily:[{date:toDate,courtCollected:m.courtCollected,transferFees:m.transferFees,policyRefunded:m.policyRefunded}],courts:state.empty?[]:[{courtId:court,courtName:'Sân cầu lông 1',...summary}],transactions:{page:p,size:25,total:state.empty?0:26,totalPages:state.empty?0:2,items:state.empty?[]:[{id:'ROW:'+p,kind:p?'POLICY_REFUND':'FACILITY_TRANSFER_FEE',direction:p?'OUT':'RECOGNITION',revenueComponent:true,cashflow:Boolean(p),countsTowardTotals:true,bookingId:bid,courtId:court,courtName:'Sân cầu lông 1',source:'ONLINE',purpose:'TRANSFER',state:'SUCCESS',amount:p?'80000':'5000',currency:'VND',effectiveAt:now}]}})
  }
  if(path===`/bookings/demo-finance/facility/${fid}`)return respond({mode:'DEMO',canPaySeller:role==='OWNER'||permissions.includes('TRANSFER_PAYOUT'),canRefund:role==='OWNER'||permissions.includes('BOOKING_REFUND'),settlements:[{...settlement,payoutState:state.payout}],refunds:[{...refundItem,state:state.refund}]})
  if(path==='/bookings/demo-finance/mine')return respond({mode:'DEMO',settlements:state.paid?[{...settlement,...(state.seller?{sellerId:actor.id}:{buyerId:actor.id}),payoutState:'FAILED'}]:[],refunds:[]})
  if(path.includes('/demo-finance/')&&method==='POST'){
   const body=request.postDataJSON();state.mutations.push({path,body,key:request.headers()['idempotency-key']});assert.deepEqual(Object.keys(body),['outcome'])
   if(state.revoke)return respond(null,403)
   if(path.endsWith('pay-seller'))state.payout=body.outcome==='SUCCESS'?'PAID':'FAILED'
   else state.refund=body.outcome==='SUCCESS'?'REFUNDED':'FAILED'
   return respond(path.endsWith('pay-seller')?{...settlement,payoutState:state.payout}:{...refundItem,state:state.refund})
  }
  if(path===`/transfers/${listing}`)return respond(listingItem)
  if(path==='/transfers/quote'){state.mutations.push({path,body:request.postDataJSON()});return respond(terms)}
  if(path==='/transfers'&&method==='POST'){state.mutations.push({path,body:request.postDataJSON()});return respond(listingItem,201)}
  if(path===`/transfers/${listing}/acquisitions`){state.mutations.push({path,body:request.postDataJSON()});return respond({id:acq},201)}
  if(path===`/transfers/acquisitions/${acq}`)return respond({id:acq,listingId:listing,bookingId:bid,buyerId:actor.id,amount:100000,currency:'VND',expiresAt:future,state:state.acquisition,paymentId:payment,financeTerms:terms})
  if(path===`/payments/${payment}`)return respond({id:payment,bookingId:bid,payerId:actor.id,acquisitionId:acq,purpose:'TRANSFER',amount:100000,currency:'VND',provider:'DEMO',status:state.paid?'SUCCESS':'PENDING',expiresAt:future})
  if(path===`/payments/${payment}/demo/complete`){state.paid=true;state.acquisition='SUCCESS';state.mutations.push({path,body:request.postDataJSON()});return respond({id:payment,purpose:'TRANSFER',status:'SUCCESS',acquisitionId:acq})}
  if(path===`/bookings/${bid}`){if(!state.seller)throw new Error('B payment view must not request booking before ownership handoff');return respond({booking:{id:bid,amount:120000,startsAt:future,currency:'VND'}})}
  if(path==='/admin/bookings/statistics')return respond({states:[]})
  if(path==='/admin/payments/statistics')return respond({orders:[],escrow:[],refundReviews:[]})
  if(path==='/admin/bookings/demo-finance')return respond({mode:'DEMO',settlements:[{...settlement,facilityName:facility.name}],refunds:[refundItem]})
  if(path==='/admin/payments/reports/collections')return respond({collections:[]})
  if(path==='/admin/facilities/statistics')return respond({regions:[]})
  if(method==='GET')return respond([])
  throw new Error(`Unexpected mutation ${method} ${path}`)
 })
 return {page,context,state}
}
try{
 const r=await fixture();await r.page.goto(`${base}/owner/facilities/${fid}/reports`)
 await r.page.getByText('125.000 ₫',{exact:true}).waitFor();assert.equal(await r.page.getByRole('img',{name:/Tiền sân/}).count(),1)
 await r.page.getByRole('button',{name:'Trang sau',exact:true}).click();await r.page.getByText('26 dòng · Trang 2/2').waitFor()
 await r.page.getByRole('button',{name:'30 ngày',exact:true}).click();await r.page.getByText('26 dòng · Trang 1/2').waitFor()
 assert.equal(new URLSearchParams(r.state.queries.at(-1)).get('page'),'0')
 const custom=async(from,to)=>{await r.page.locator('input[name=fromDate]').fill(from);await r.page.locator('input[name=toDate]').fill(to);await r.page.getByRole('button',{name:'Xem báo cáo',exact:true}).click()}
 r.state.delayDate='2026-10-01';await custom('2026-10-01','2026-10-03');await custom('2026-10-04','2026-10-06')
 await r.page.getByText('06/10/2026',{exact:true}).first().waitFor();await r.page.waitForTimeout(650)
 assert.ok(await r.page.getByText('06/10/2026',{exact:true}).count());assert.equal(await r.page.getByText('03/10/2026',{exact:true}).count(),0)
 const before=r.state.queries.length;await custom('2025-01-01','2026-10-01');await r.page.getByRole('alert').filter({hasText:'tối đa 366'}).waitFor();assert.equal(r.state.queries.length,before)
 await custom('2026-10-04','2026-10-06');await r.page.getByText('125.000 ₫',{exact:true}).waitFor()
 const refresh=async()=>{await r.page.getByRole('button',{name:'Làm mới báo cáo',exact:true}).click()}
 r.state.availability='PARTIAL';await refresh();await r.page.getByRole('alert').filter({hasText:'phần đã ghi nhận'}).waitFor()
 r.state.availability='UNAVAILABLE';await refresh();await r.page.getByRole('alert').filter({hasText:'không phải doanh thu bằng 0'}).waitFor();assert.ok(await r.page.getByText('Chưa có dữ liệu',{exact:true}).count())
 r.state.fail=true;await refresh();await r.page.getByRole('alert').filter({hasText:'Không thể thực hiện yêu cầu'}).waitFor();r.state.fail=false;r.state.availability='COMPLETE';r.state.empty=true;await refresh();await r.page.getByText('Không có giao dịch phát sinh trong kỳ đã chọn.').waitFor()
 r.state.empty=false;await refresh();await r.page.getByText('125.000 ₫',{exact:true}).waitFor()
 fs.mkdirSync('tmp/demo-ui-evidence',{recursive:true});await r.page.screenshot({path:'tmp/demo-ui-evidence/report-desktop.png',fullPage:true})
 await r.page.setViewportSize({width:390,height:844});await r.page.screenshot({path:'tmp/demo-ui-evidence/report-mobile.png',fullPage:true})
 assert.equal(await r.page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth+2),false)
 console.log('PASS report exact totals/chart/table/range/datezone/pagination/stale response/error/empty/partial/mobile')
 const op=await fixture();await op.page.goto(`${base}/owner/bookings?facilityId=${fid}`);await op.page.getByRole('heading',{name:'Vận hành lịch đặt'}).waitFor();assert.equal(await op.page.getByRole('heading',{name:/Trả người chuyển nhượng/}).count(),0);assert.equal(await op.page.getByRole('button',{name:'Trả tiền mô phỏng cho A',exact:true}).count(),0)
 const staff=await fixture('STAFF',['BOOKING_READ','TRANSFER_PAYOUT','BOOKING_REFUND']);await staff.page.goto(`${base}/staff/bookings`);await staff.page.getByRole('heading',{name:'Kiểm tra lượt đặt'}).waitFor();assert.equal(await staff.page.getByRole('heading',{name:/Trả người chuyển nhượng/}).count(),0);assert.equal(op.state.mutations.length+staff.state.mutations.length,0)
 console.log('PASS Owner and Staff operations pages no longer show seller payout or refund execution')
 const buyer=await fixture('CUSTOMER');await buyer.page.goto(`${base}/transfers/${listing}`);const acquireButton=buyer.page.getByRole('button',{name:'Nhận lượt đặt này',exact:true});await acquireButton.waitFor();assert.equal(await acquireButton.isDisabled(),true)
 await buyer.page.getByRole('checkbox').check();await acquireButton.click();await buyer.page.waitForURL(`**/customer/transfers/acquisitions/${acq}`);assert.equal(buyer.state.mutations[0].body.acceptedTermsHash,terms.termsHash)
 await buyer.page.goto(`${base}/customer/payments/${payment}`);await buyer.page.getByRole('button',{name:'Thanh toán mô phỏng',exact:true}).click();await buyer.page.waitForURL(`**/customer/transfers/acquisitions/${acq}`);await buyer.page.getByRole('link',{name:'Mở lượt đặt và mã xác nhận vào sân của bạn'}).waitFor();await buyer.page.getByText('Bạn đã thanh toán cho cơ sở và nhận quyền.',{exact:false}).waitFor();assert.equal(await buyer.page.getByRole('button',{name:'Trả tiền mô phỏng cho A',exact:true}).count(),0)
 console.log('PASS B explicit terms and payment DEMO without bank/early booking access, rights independent from failed A payout')
 const seller=await fixture('CUSTOMER');seller.state.seller=true;seller.state.paid=true
 await seller.page.goto(`${base}/customer/bookings/${bid}/transfer`);await seller.page.locator('input[name=price]').fill('100000');await seller.page.getByRole('button',{name:'Xem phí và điều khoản',exact:true}).click();await seller.page.getByRole('heading',{name:'Điều khoản chuyển nhượng mô phỏng'}).waitFor()
 const publish=seller.page.getByRole('button',{name:'Đăng tin chuyển nhượng',exact:true});assert.equal(await publish.isDisabled(),true);assert.equal(await seller.page.locator('input[name*=bank],input[name*=beneficiary]').count(),0)
 await seller.page.getByRole('checkbox').check();await publish.click();await seller.page.waitForURL(`**/transfers/${listing}`);assert.equal(seller.state.mutations.find(m=>m.path==='/transfers').body.acceptedTermsHash,terms.termsHash)
 await seller.page.goto(`${base}/customer/transfers`);await seller.page.getByText('Cơ sở phải trả bạn 95.000 ₫ sau phí 5.000 ₫.',{exact:true}).waitFor();assert.equal(await seller.page.getByRole('button',{name:'Trả tiền mô phỏng cho A',exact:true}).count(),0)
 const admin=await fixture('ADMIN');await admin.page.goto(`${base}/admin/reports`);await admin.page.getByRole('heading',{name:'Giám sát hệ thống mô phỏng',exact:true}).waitFor();await admin.page.getByRole('row').filter({hasText:'Trả người bán · Cụm sân DEMO'}).waitFor();assert.equal(await admin.page.getByRole('button',{name:'Trả tiền mô phỏng cho A',exact:true}).count(),0);assert.equal(await admin.page.getByRole('button',{name:'Hoàn tiền mô phỏng',exact:true}).count(),0)
 console.log('PASS A quote/consent/no banking inputs/own unpaid status; mutation refreshes report in another tab; Admin monitor read only')
 assert.deepEqual(errors,[])
}finally{for(const context of contexts)await context.close();await browser.close()}
