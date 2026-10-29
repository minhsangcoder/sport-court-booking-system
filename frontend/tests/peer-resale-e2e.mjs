import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { fixture } from '../../scripts/performance/ui-fixture.mjs'
import { facility, id } from '../../scripts/performance/ui-data.mjs'

const root = fileURLToPath(new URL('../../', import.meta.url))
const directory = path.resolve(process.argv[2] || path.join(root,'frontend'))
const output = path.resolve(process.env.SPORTHUB_PEER_E2E_OUTPUT || path.join(root,'tmp/peer-resale-performance/peer-browser'))
mkdirSync(output,{recursive:true})
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.SPORTHUB_E2E_PLAYWRIGHT || 'playwright')
const server = spawn(process.execPath,[path.join(root,'scripts/performance/ui-server.mjs'),directory],{windowsHide:true,stdio:['ignore','pipe','pipe'],env:{...process.env,NODE_ENV:'production',GATEWAY_URL:'http://127.0.0.1:1'}})
let browser, logs=''
const errors=[], calls=[]
try {
  const base=await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(Error(`UI fixture timeout: ${logs.slice(-1500)}`)),55000)
    server.on('error',reject)
    server.on('exit',code=>{clearTimeout(timer);reject(Error(`UI fixture exit ${code}: ${logs.slice(-1500)}`))})
    server.stderr.on('data',data=>{logs=(logs+data).slice(-20000)})
    server.stdout.on('data',data=>{logs=(logs+data).slice(-20000);for(const line of logs.split('\n'))try{const value=JSON.parse(line);if(value.production&&value.url){clearTimeout(timer);resolve(value.url)}}catch{}})
  })
  browser=await chromium.launch({headless:true,channel:process.env.SPORTHUB_E2E_BROWSER_CHANNEL || 'chrome'})
  const seller=id(301), buyer=id(302), acquisition=id(303), listing=id(304), booking=id(305), conversation=id(306), photo=id(307), revision=id(308), caseId=id(309)
  const now=Date.now(), starts=new Date(now+3*3600000).toISOString(), ends=new Date(now+4*3600000).toISOString(), expires=new Date(now+5*60000).toISOString()
  const sellerTerms={protocol:'PEER_RESALE_V1',mode:'REAL',moneyClass:'REAL',amount:'80000',currency:'VND',termsHash:'a'.repeat(64),bankSharingConsent:'BUYER_AND_SEPAY_QR_V1',refundPolicy:{configured:true,id:id(333),version:1,rateBps:8000,currency:'VND',rounding:'HALF_UP',termsHash:'c'.repeat(64)},sellerId:seller,bankDestinationRevisionId:revision,destinationHash:'b'.repeat(64),bookingId:booking,facilityId:facility.id,courtId:id(11),originalAmount:'120000',startsAt:starts,endsAt:ends,deadline:starts}
  const terms={...sellerTerms,termsHash:'e'.repeat(64),sellerTermsHash:sellerTerms.termsHash,listingId:listing,listingVersion:0,acquisitionId:acquisition,buyerId:buyer,expiresAt:expires,acceptedAt:new Date(now).toISOString()}
  const original={id:acquisition,listingId:listing,bookingId:booking,buyerId:buyer,amount:80000,currency:'VND',expiresAt:expires,state:'PENDING',paymentId:null,financeTerms:terms,protocol:'PEER_RESALE_V1',conversationId:conversation}
  const attempt={id:acquisition,listingId:listing,bookingId:booking,sellerId:seller,buyerId:buyer,protocol:'PEER_RESALE_V1',phase:'WAITING_PAYMENT',version:0,amount:80000,currency:'VND',payBy:expires,usageDecisionBy:starts,conversationId:conversation,termsHash:terms.termsHash,confirmationMethod:'NONE',decision:null}
  const caseAttempt={...attempt,id:caseId,phase:'DISPUTED',version:2}
  const bank={id:revision,principalId:seller,guestBookingId:null,bankBin:'970436',bankName:'Vietcombank',maskedAccountNumber:'***6789',accountName:'NGUYEN VAN A',destinationHash:'b'.repeat(64)}
  let bankState={principalId:seller,version:0,initialized:false,canRegisterFirst:true,canChange:false,activeRevisionId:null,destination:null,responsibilities:[{kind:'BOOKING',resourceId:booking,effectiveUntil:ends}]}, bankUnavailable=false, bankRace=false
  const photos=[], messages=[], returns=[], reports=[], noClaims=[]
  let moneyState='NONE', declaration=null, failQr=true
  const receiptDenials=[401,403]
  let slowPeerPoll=false
  const png=readFileSync(path.join(root,'backend/payment-service/src/test/resources/peer-qr/sepay-vcb-synthetic.png'))
  const moneyView=()=>({acquisitionId:acquisition,amount:'80000',currency:'VND',termsHash:terms.termsHash,authority:'COUNTERPARTIES_ONLY',state:moneyState,noClaims:[...noClaims],returns:[...returns],reports:[...reports],returnPage:{more:false,nextAfter:null},totals:{declared:String(returns.reduce((sum,r)=>sum+Number(r.amount),0)),received:String(returns.filter(r=>r.state==='BUYER_ACKNOWLEDGED').reduce((sum,r)=>sum+Number(r.amount),0)),awaitingBuyer:String(returns.filter(r=>r.state==='AWAITING_BUYER').reduce((sum,r)=>sum+Number(r.amount),0)),records:returns.length,pending:returns.filter(r=>r.state==='AWAITING_BUYER').length}})
  const caseView=()=>({attempt:caseAttempt,booking:{id:booking,status:'CONFIRMED',currentHolderId:seller,nativeAmount:120000,currency:'VND',startsAt:starts,endsAt:ends},moneyCase:{state:'OPEN',reason:'B khai đã trả nhưng A chưa nhận'},money:{...moneyView(),state:'OPEN'},declaration:{reference:'TX123',evidenceIds:[photo],claimedAt:new Date().toISOString()},presence:[],history:[],authority:'USAGE_ONLY'})

  async function screen(role,userId) {
    const context=await browser.newContext({locale:'vi-VN',timezoneId:'Asia/Ho_Chi_Minh',viewport:{width:1100,height:900}})
    const fallback=fixture(role);fallback.user.id=userId
    await context.route('**/api/v1/**',async route=>{
      const request=route.request(),url=new URL(request.url()),uri=url.pathname.replace('/api/v1',''),method=request.method()
      let input;try{input=request.postDataJSON()}catch{}
      calls.push({role,userId,uri,method,input,key:request.headers()['idempotency-key']})
      const reply=(data,status=200,message='Dữ liệu thử')=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({data,message})})
      if(uri==='/payments/runtime')return reply({mode:'REAL',realEnabled:true,demoEnabled:false})
      if(uri===`/bookings/reports/facility/${facility.id}/dashboard`) {
        const cash={courtCollected:'120000',transferFees:'0',operatingRevenue:'120000',policyRefunded:'0',afterRefund:'120000',transferCollected:'0',sellerPaid:'0',sellerDue:'0',refundDue:'0',technicalRefunded:'0',bankCollected:'120000',unappliedCollected:'0'}
        const summary={bookingCount:1,fullyPaidBookingCount:1,bookedMinutes:'60',usedMinutes:'0',cancelledCount:0,expiredCount:0,money:cash}
        const at=new Date().toISOString(),fromDate=url.searchParams.get('fromDate'),toDate=url.searchParams.get('toDate')
        return reply({facilityId:facility.id,mode:'REAL',label:'Thu ngân hàng đã xác minh; chi do cơ sở ghi nhận',asOf:at,obligationCutoff:at,window:{fromDate,toDate,from:`${fromDate}T00:00:00+07:00`,to:at,timezone:'Asia/Ho_Chi_Minh',interval:'[from,to)'},moneyAvailability:'COMPLETE',reasons:[],summary,daily:[{date:toDate,...cash}],courts:[{courtId:id(11),courtName:'Sân đo 1',...summary}],transactions:{page:0,size:25,total:1,totalPages:1,items:[{id:'BANK:fixture',kind:'BANK_CREDIT',direction:'IN',revenueComponent:false,cashflow:true,countsTowardTotals:true,bookingId:booking,courtId:id(11),courtName:'Sân đo 1',source:'ONLINE',purpose:'BOOKING',state:'BANK_VERIFIED',amount:'120000',currency:'VND',effectiveAt:at,moneyClass:'REAL',confirmationMethod:'BANK_VERIFIED',dateBasis:'bankTime'}]},peer:{availability:'COMPLETE',reasons:[],dateBasis:'Separate peer declaration/acknowledgment',openUsageCases:0,openMoneyCases:1,buyerDeclaredAmount:'80000',sellerAcknowledgedAmount:'80000',ownerAwardedAmount:'0',returnDeclaredAmount:'40000',returnAcknowledgedAmount:'40000',transactions:{page:0,size:25,total:1,totalPages:1,items:[{acquisitionId:acquisition,bookingId:booking,courtId:id(11),payerId:buyer,recipientId:seller,protocol:'PEER_RESALE_V1',amount:'80000',phase:'COMPLETED',confirmationMethod:'COUNTERPARTY_ACKNOWLEDGED',usageDecision:'COMPLETED',moneyState:'OPEN',createdAt:at,declaredAt:at,decidedAt:at}]}}})
      }
      if(uri==='/payments/bank-profile') return bankUnavailable ? reply(null,503,'Chưa kiểm tra được nghĩa vụ ngân hàng.') : reply(bankState)
      if(uri==='/payments/bank-destinations') {
        if(method==='GET')return reply(bankState.initialized?[bank]:[])
        if(bankRace) {assert.equal(input.expectedProfileVersion,1);assert.equal(input.accountNumber,'000987654321');bankRace=false;bankState={...bankState,canChange:false,responsibilities:[{kind:'BOOKING',resourceId:booking,effectiveUntil:ends}]};return reply(null,409,'Bạn vừa có lịch sân mới, chưa thể đổi tài khoản ngân hàng.')}
        assert.equal(userId,seller);assert.equal(input.accountNumber,'000123456789');assert.equal(input.expectedProfileVersion,0);assert.ok(input.consentReference);assert.ok(request.headers()['idempotency-key'])
        bankState={...bankState,version:1,initialized:true,canRegisterFirst:false,activeRevisionId:revision,destination:bank};return reply(bank)
      }
      if(uri==='/payments/banks')return reply({updatedAt:new Date().toISOString(),stale:false,banks:[{bin:'970436',name:'Vietcombank',shortName:'VCB'}]})
      if(uri==='/bookings/admission')return reply({policyVersion:1,maxConcurrent:1,policyAvailability:'COMPLETE',holdBlockedUntil:null,onlineBlockedUntil:null,marketBlockedUntil:null,marketPermanent:false,strikeCount:0,resources:[]})
      if(uri===`/bookings/${booking}`)return reply({booking:{id:booking,facilityId:facility.id,courtId:id(11),customerId:seller,currentHolderId:attempt.phase==='COMPLETED'?buyer:seller,createdBy:seller,amount:120000,currency:'VND',startsAt:starts,endsAt:ends,status:'CONFIRMED',source:'ONLINE',priceSnapshot:{moneyClass:'REAL',segments:[],refundPolicy:sellerTerms.refundPolicy}},history:[],checkinToken:null,resaleConsumed:attempt.phase==='COMPLETED'})
      if(uri==='/transfers/quote') {assert.equal(input.bankDestinationRevisionId,revision);return reply({...sellerTerms,amount:String(input.price),termsHash:input.price===80000?'a'.repeat(64):'d'.repeat(64)})}
      if(uri==='/transfers'&&method==='POST') {assert.equal(userId,seller);assert.equal(input.price,80000);assert.equal(input.acceptedTermsHash,sellerTerms.termsHash);assert.equal(input.bankDestinationRevisionId,revision);assert.ok(request.headers()['idempotency-key']);return reply({id:listing})}
      if(uri===`/transfers/${listing}`)return reply({id:listing,facilityId:facility.id,courtId:id(11),venue:{facilityName:facility.name,courtName:'Sân đo 1',address:'Cơ sở thử',timezone:'Asia/Ho_Chi_Minh',amenities:[]},originalAmount:120000,price:80000,currency:'VND',startsAt:starts,endsAt:ends,deadline:starts,state:'ACTIVE',own:userId===seller,available:true,version:0,financeTerms:{protocol:'PEER_RESALE_V1',mode:'REAL',currency:'VND',amount:'80000',termsHash:sellerTerms.termsHash,refundPolicy:sellerTerms.refundPolicy,bankSharingConsent:sellerTerms.bankSharingConsent}})
      if(uri===`/transfers/${listing}/acquisitions`) {assert.equal(userId,buyer);assert.equal(input.acceptedTermsHash,sellerTerms.termsHash);return reply(original)}
      if(uri===`/transfers/acquisitions/${acquisition}`)return reply(original)
      if(uri===`/transfers/acquisitions/${acquisition}/peer`) {
        if(slowPeerPoll) {await new Promise(resolve=>setTimeout(resolve,1500));return reply(attempt).catch(()=>{})}
        return reply(attempt)
      }
      if(uri===`/payments/peer-acquisitions/${acquisition}/instruction`)return reply({amount:'80000',description:'SH1',bankName:'Vietcombank',maskedAccountNumber:'***6789',accountName:'NGUYEN VAN A',expiresAt:expires})
      if(uri===`/payments/peer-acquisitions/${acquisition}/qr` && failQr) {failQr=false;return reply(null,503,'Ảnh QR tạm chưa sẵn sàng.')}
      if(uri===`/payments/peer-acquisitions/${acquisition}/qr`||uri.startsWith('/transfers/evidence/'))return route.fulfill({status:200,contentType:'image/png',body:png,headers:{'Cache-Control':'private, no-store'}})
      if(uri===`/transfers/conversations/${conversation}/messages`) {
        if(method==='POST') { const row={id:id(400+messages.length),cursor:messages.length+1,senderId:userId,text:input.text,createdAt:new Date().toISOString()};messages.push(row);return reply(row) }
        const after=url.searchParams.get('after'),before=url.searchParams.get('before'),size=Number(url.searchParams.get('size')||50)
        return reply(after!==null ? messages.filter(row=>row.cursor>Number(after)).slice(0,size) : messages.filter(row=>before===null||row.cursor<Number(before)).slice(-size))
      }
      if(/^\/transfers\/acquisitions\/[^/]+\/evidence$/.test(uri)) {
        if(method==='POST') { const row={id:photos.length ? id(330+photos.length) : photo,acquisitionId:acquisition,uploaderId:userId,contentType:'image/png',size:png.length,state:'READY',createdAt:new Date().toISOString()};photos.push(row);return reply(row) }
        return reply(photos)
      }
      if(uri===`/transfers/acquisitions/${acquisition}/declaration`)return reply(declaration)
      if(uri===`/transfers/acquisitions/${acquisition}/declare-payment`) { assert.equal(userId,buyer);assert.equal(input.version,0);assert.deepEqual(input.evidenceIds,[photo]);assert.equal(input.bankTransactionReference,'TX123');declaration={reference:'TX123',evidenceIds:[photo],claimedAt:new Date().toISOString()};attempt.phase='PAYMENT_DECLARED';attempt.version=1;attempt.confirmationMethod='BUYER_DECLARED';return reply(attempt) }
      if(uri===`/transfers/acquisitions/${acquisition}/confirm-receipt`) { assert.equal(userId,seller);assert.deepEqual(Object.keys(input).sort(),['termsHash','version']);const denial=receiptDenials.shift();if(denial)return reply(null,denial,'Phiên thử yêu cầu xác thực lại trước khi thao tác.');attempt.phase='COMPLETED';attempt.version=3;attempt.confirmationMethod='COUNTERPARTY_ACKNOWLEDGED';attempt.decision={decision:'BUYER_RECEIVES',reason:'A xác nhận đã nhận đủ tiền',decidedAt:new Date().toISOString()};return reply(attempt) }
      if(uri===`/transfers/acquisitions/${acquisition}/money`)return reply(moneyView())
      if(uri===`/transfers/acquisitions/${acquisition}/report-late-payment`) {moneyState='OPEN';reports.push({id:id(500+reports.length),reporterId:userId,reference:input.bankTransactionReference,evidenceIds:input.evidenceIds,reportedAt:new Date().toISOString()});return reply(attempt)}
      if(uri===`/transfers/acquisitions/${acquisition}/returns`) {assert.equal(userId,seller);assert.equal(input.amount,40000);returns.push({id:id(520),amount:'40000',state:'AWAITING_BUYER',version:0,reference:input.bankTransactionReference,evidenceIds:input.evidenceIds,confirmationMethod:'NONE'});return reply(attempt)}
      if(uri===`/transfers/acquisitions/${acquisition}/returns/${id(520)}/acknowledge`) {assert.equal(userId,buyer);returns[0].state='BUYER_ACKNOWLEDGED';returns[0].confirmationMethod='COUNTERPARTY_ACKNOWLEDGED';return reply(attempt)}
      if(uri===`/transfers/facilities/${facility.id}/cases/presence-context`)return reply([{...caseAttempt,courtId:id(11),startsAt:starts,endsAt:ends}])
      if(uri===`/transfers/facilities/${facility.id}/cases`) {assert.equal(role,'OWNER');return reply([caseAttempt])}
      if(uri===`/transfers/facilities/${facility.id}/cases/${caseId}`) {assert.equal(role,'OWNER');return reply(caseView())}
      if(uri===`/payments/peer-acquisitions/${caseId}/case-recipient`) {assert.equal(role,'OWNER');return reply(bank)}
      if(uri===`/transfers/facilities/${facility.id}/cases/${caseId}/resolve`) {assert.equal(role,'OWNER');assert.deepEqual(Object.keys(input).sort(),['evidenceIds','outcome','reason','version']);assert.equal(input.outcome,'SELLER_RETAINS');caseAttempt.phase='ABORTED_A_RETAINS';caseAttempt.version++;caseAttempt.decision={decision:'SELLER_RETAINS',reason:input.reason,decidedAt:new Date().toISOString()};return reply(caseAttempt)}
      if(uri===`/transfers/facilities/${facility.id}/cases/${caseId}/presence`) {assert.ok(['OWNER','STAFF'].includes(role));assert.deepEqual(Object.keys(input).sort(),['party','reason']);return reply(caseAttempt)}
      return fallback.route(route)
    })
    const page=await context.newPage();page.on('pageerror',failure=>errors.push(failure.message));page.on('dialog',dialog=>dialog.accept())
    return {page,context}
  }
  const A=await screen('CUSTOMER',seller),B=await screen('CUSTOMER',buyer)
  await B.page.setViewportSize({width:390,height:844})
  await A.page.goto(`${base}/customer/bookings/${booking}/transfer`)
  await A.page.getByText('Bạn được khai tài khoản lần đầu, kể cả khi đang có lịch sân.').waitFor()
  await A.page.locator('input[name=price]').fill('80000')
  await A.page.getByRole('button',{name:'Xem điều khoản',exact:true}).click()
  await A.page.getByText('Chọn tài khoản ngân hàng của bạn trước khi xem điều khoản và đăng tin.').waitFor()
  assert.equal(calls.filter(row=>row.uri==='/transfers/quote').length,0)
  await A.page.goto(`${base}/customer/profile/bank`)
  await A.page.getByText('Bạn được khai tài khoản lần đầu, kể cả khi đang có lịch sân.').waitFor()
  await A.page.getByRole('combobox',{name:/Ngân hàng nhận/}).selectOption('970436')
  await A.page.getByLabel('Số tài khoản',{exact:true}).fill('000123456789')
  await A.page.getByLabel('Tên người nhận (tự khai)',{exact:true}).fill('NGUYEN VAN A')
  await A.page.getByLabel('Tôi đã kiểm tra ngân hàng, số tài khoản và đồng ý dùng thông tin này nhận tiền.').check()
  await A.page.getByRole('button',{name:'Lưu tài khoản nhận',exact:true}).click()
  await A.page.getByText('Chưa thể thay, thêm tài khoản thay thế hoặc chọn tài khoản khác.').waitFor()
  assert.equal(await A.page.getByRole('button',{name:'Lưu tài khoản nhận',exact:true,includeHidden:true}).isDisabled(),true)
  await A.page.screenshot({path:path.join(output,'bank-locked.png'),fullPage:true})
  await A.page.goto(`${base}/customer/bookings/${booking}/transfer`)
  await A.page.getByRole('combobox',{name:/Chọn tài khoản đã khai/}).waitFor()
  assert.equal(await A.page.getByRole('combobox',{name:/Chọn tài khoản đã khai/}).inputValue(),revision)
  assert.equal(await A.page.getByRole('combobox',{name:/Chọn tài khoản đã khai/}).isDisabled(),true)
  await A.page.locator('input[name=price]').fill('80000')
  await A.page.getByRole('button',{name:'Xem điều khoản',exact:true}).click()
  await A.page.getByRole('checkbox',{name:/Tôi đồng ý điều khoản, chia sẻ tài khoản/}).check()
  await A.page.locator('input[name=price]').fill('70000')
  assert.equal(await A.page.getByRole('button',{name:'Đăng tin chuyển nhượng',exact:true}).count(),0)
  await A.page.locator('input[name=price]').fill('80000')
  await A.page.getByRole('button',{name:'Xem điều khoản',exact:true}).click()
  assert.equal(await A.page.getByRole('checkbox',{name:/Tôi đồng ý điều khoản, chia sẻ tài khoản/}).isChecked(),false)
  await A.page.getByRole('checkbox',{name:/Tôi đồng ý điều khoản, chia sẻ tài khoản/}).check()
  await A.page.getByRole('button',{name:'Đăng tin chuyển nhượng',exact:true}).click()
  await A.page.waitForURL(`**/transfers/${listing}`)
  await B.page.goto(`${base}/transfers/${listing}`)
  await B.page.getByRole('checkbox',{name:/Tôi đồng ý trả trực tiếp người bán/}).check()
  await B.page.getByRole('button',{name:'Mua lượt đặt này',exact:true}).click()
  await B.page.getByRole('button',{name:'Tải lại ảnh',exact:true}).click()
  await B.page.getByRole('img',{name:'QR SePay chuyển tiền đúng giá cho người bán'}).waitFor()
  slowPeerPoll=true
  await B.page.waitForRequest(request=>request.url().endsWith(`/transfers/acquisitions/${acquisition}/peer`))
  const aborted=B.page.waitForEvent('requestfailed',{predicate:request=>request.url().endsWith(`/transfers/acquisitions/${acquisition}/peer`)})
  await B.page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'))})
  await aborted
  const pausedCount=calls.filter(row=>row.userId===buyer&&row.uri===`/transfers/acquisitions/${acquisition}/peer`).length
  await B.page.waitForTimeout(4500)
  assert.equal(calls.filter(row=>row.userId===buyer&&row.uri===`/transfers/acquisitions/${acquisition}/peer`).length,pausedCount)
  slowPeerPoll=false
  const resumed=B.page.waitForRequest(request=>request.url().endsWith(`/transfers/acquisitions/${acquisition}/peer`))
  await B.page.evaluate(()=>{delete document.hidden;document.dispatchEvent(new Event('visibilitychange'))})
  await resumed
  await B.page.getByLabel('Tin nhắn (tối đa 10 tin/phút)').fill('<img src=x onerror="window.peerXss=true">')
  await B.page.getByRole('button',{name:'Gửi tin nhắn',exact:true}).click()
  await B.page.getByText('<img src=x onerror="window.peerXss=true">',{exact:true}).waitFor()
  assert.equal(await B.page.evaluate(()=>window.peerXss),undefined)
  const unrelatedBefore=calls.filter(row=>row.uri.startsWith('/bookings/availability')||row.uri.includes('/reports')||row.uri==='/payments/bank-profile').length
  await B.page.getByLabel('Tải ảnh mới (tối đa 10 ảnh/giờ)').setInputFiles({name:'bill.png',mimeType:'image/png',buffer:png})
  await B.page.getByLabel('Mã giao dịch ngân hàng',{exact:true}).fill('TX123')
  await B.page.getByRole('button',{name:'Tôi đã chuyển tiền, gửi chứng từ',exact:true}).click()
  await B.page.getByText('Người mua đã khai chuyển tiền',{exact:true}).waitFor()
  await B.page.reload()
  await B.page.getByText('Người mua đã khai chuyển tiền',{exact:true}).waitFor()
  assert.equal(calls.filter(row=>row.uri.startsWith('/bookings/availability')||row.uri.includes('/reports')||row.uri==='/payments/bank-profile').length,unrelatedBefore)
  await A.page.goto(`${base}/customer/transfers/acquisitions/${acquisition}`)
  for(let denied=1;denied<=2;denied++) {
    const confirm=A.page.getByRole('button',{name:'Đã nhận đủ tiền, bàn giao sân',exact:true})
    await confirm.click()
    await A.page.getByText('Phiên thử yêu cầu xác thực lại trước khi thao tác.',{exact:true}).waitFor()
    assert.equal(await confirm.isDisabled(),true)
    await A.page.waitForTimeout(300)
    assert.equal(calls.filter(row=>row.uri===`/transfers/acquisitions/${acquisition}/confirm-receipt`).length,denied)
    assert.equal(attempt.phase,'PAYMENT_DECLARED')
    await A.page.reload()
  }
  await A.page.getByRole('button',{name:'Đã nhận đủ tiền, bàn giao sân',exact:true}).click()
  await A.page.getByText('Đã bàn giao cho người mua',{exact:true}).waitFor()
  assert.equal(calls.some(row=>row.uri==='/payments'&&row.method==='POST'),false)
  assert.equal(calls.some(row=>row.uri.includes('payout')||row.uri.includes('refund')&&row.method==='POST'),false)
  await B.page.getByRole('button',{name:'Tải lại trạng thái quyền sân',exact:true}).click()
  await B.page.getByRole('link',{name:'Mở lượt đặt và mã vào sân của bạn',exact:true}).waitFor()
  await B.page.screenshot({path:path.join(output,'buyer-handoff.png'),fullPage:true})
  assert.equal(await B.page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth),true)
  await B.page.getByRole('link',{name:'Mở lượt đặt và mã vào sân của bạn',exact:true}).click()
  await B.page.getByText('Lượt đặt đã chuyển nhượng một lần, không thể đăng bán tiếp.').waitFor()
  assert.equal(await B.page.getByRole('link',{name:'Đăng tin chuyển nhượng trực tiếp',exact:true}).count(),0)
  await B.page.goto(`${base}/customer/transfers/acquisitions/${acquisition}`)
  await B.page.getByRole('button',{name:'Đối chiếu tiền / báo tiền muộn',exact:true}).click()
  await B.page.getByLabel('Mã giao dịch ngân hàng',{exact:true}).fill('LATE123')
  await B.page.getByRole('button',{name:'Gửi lời khai và chứng từ',exact:true}).waitFor()
  await B.page.getByRole('checkbox',{name:/Chọn ảnh/}).check()
  await B.page.getByRole('button',{name:'Gửi lời khai và chứng từ',exact:true}).click()
  await B.page.getByText('Còn cần hai bên đối chiếu',{exact:true}).waitFor()
  await A.page.getByRole('button',{name:'Đối chiếu tiền / báo tiền muộn',exact:true}).click()
  await A.page.getByLabel('Thao tác với tiền').selectOption('returns')
  await A.page.getByLabel('Số tiền A đã chuyển trả (VND)').fill('40000')
  await A.page.getByLabel('Tải ảnh mới (tối đa 10 ảnh/giờ)').setInputFiles({name:'return.png',mimeType:'image/png',buffer:png})
  await A.page.getByLabel('Mã giao dịch ngân hàng',{exact:true}).fill('RETURN123')
  await A.page.getByRole('button',{name:'Gửi lời khai và chứng từ',exact:true}).click()
  await A.page.getByText('Người bán khai đã trả; chờ người mua xác nhận',{exact:false}).waitFor()
  await B.page.getByRole('button',{name:'Tải lại hồ sơ tiền',exact:true}).click()
  await B.page.getByRole('button',{name:'Đã nhận khoản trả lại này',exact:true}).click()
  await B.page.getByText('Người mua xác nhận đã nhận',{exact:false}).first().waitFor()
  assert.equal(moneyState,'OPEN')
  const S=await screen('STAFF',id(320))
  await S.page.goto(`${base}/staff/peer-presence?facilityId=${facility.id}`)
  await S.page.getByLabel('Phiên chuyển nhượng').selectOption(caseId)
  await S.page.getByLabel('Căn cứ ghi nhận').fill('Đã đối chiếu người mua có mặt tại quầy')
  await S.page.getByRole('button',{name:'Ghi nhận có mặt thực tế',exact:true}).click()
  assert.equal(await S.page.getByRole('button',{name:'Chốt quyền sử dụng sân',exact:true}).count(),0)
  const O=await screen('OWNER',id(2))
  await O.page.goto(`${base}/owner/facilities/${facility.id}/peer-cases`)
  await O.page.getByRole('button',{name:/Đang tranh chấp quyền sân/}).click()
  await O.page.getByRole('heading',{name:'Hồ sơ quyền sử dụng sân',exact:true}).waitFor()
  assert.equal(await O.page.getByRole('button',{name:'Đã nhận khoản trả lại này',exact:true}).count(),0)
  assert.equal(await O.page.locator('input[type=number]').count(),0)
  await O.page.getByLabel('Lý do và căn cứ').fill('Chốt quyền sử dụng cho A dựa trên chứng cứ tại sân')
  await O.page.getByRole('button',{name:'Chốt quyền sử dụng sân',exact:true}).click()
  await O.page.getByText(/Đã có quyết định cuối:/).waitFor()
  await O.page.screenshot({path:path.join(output,'owner-usage-only.png'),fullPage:true})
  await O.page.goto(`${base}/owner/facilities/${facility.id}/reports`)
  await O.page.getByRole('heading',{name:'Chuyển nhượng trực tiếp B → A',exact:true}).waitFor()
  const revenue=O.page.locator('article').filter({has:O.page.getByRole('heading',{name:'Tổng khoản thu kinh doanh',exact:true})})
  assert.match(await revenue.innerText(),/120\.000/)
  await O.page.getByText('A xác nhận đã nhận',{exact:true}).waitFor()
  await O.page.getByText('A/B còn cần đối chiếu',{exact:true}).waitFor()
  await O.page.screenshot({path:path.join(output,'report-peer.png'),fullPage:true})
  bankState={...bankState,canChange:true,responsibilities:[]}
  await A.page.goto(`${base}/customer/profile/bank`)
  await A.page.getByText('Bạn có thể thay tài khoản nhận tiền.').waitFor()
  await A.page.getByText('Khai tài khoản nhận mới',{exact:true}).click()
  await A.page.getByRole('combobox',{name:/Ngân hàng nhận/}).selectOption('970436')
  await A.page.getByLabel('Số tài khoản',{exact:true}).fill('000987654321')
  await A.page.getByLabel('Tôi đã kiểm tra ngân hàng, số tài khoản và đồng ý dùng thông tin này nhận tiền.').check()
  bankRace=true
  await A.page.getByRole('button',{name:'Lưu tài khoản nhận',exact:true}).click()
  await A.page.getByText('Chưa thể thay, thêm tài khoản thay thế hoặc chọn tài khoản khác.').waitFor()
  assert.equal(await A.page.getByRole('combobox',{name:/Chọn tài khoản đã khai/}).isDisabled(),true)
  bankUnavailable=true
  await A.page.goto(`${base}/customer/profile/bank`)
  await A.page.getByText('Chưa kiểm tra được nghĩa vụ ngân hàng.').waitFor()
  assert.equal(await A.page.getByRole('button',{name:'Lưu tài khoản nhận',exact:true,includeHidden:true}).isDisabled(),true)
  const publicScreen=await screen('PUBLIC',id(399)), publicScripts=[]
  publicScreen.page.on('response',response=>{if(response.url().includes('/_next/static/')&&new URL(response.url()).pathname.endsWith('.js'))publicScripts.push(response.body().catch(()=>Buffer.alloc(0)))})
  await publicScreen.page.goto(`${base}/search`)
  await publicScreen.page.getByText('Sân đo 1',{exact:false}).first().waitFor()
  await publicScreen.page.waitForLoadState('networkidle')
  const commonChunks=await Promise.all(publicScripts),commonCode=Buffer.concat(commonChunks).toString('utf8')
  for(const privateLabel of ['Khai tài khoản nhận mới','QR SePay chuyển tiền đúng giá cho người bán','Tin nhắn (tối đa 10 tin/phút)'])assert.equal(commonCode.includes(privateLabel),false,`Private feature eagerly bundled on search: ${privateLabel}`)
  assert.deepEqual(errors,[])
  writeFileSync(path.join(output,'result.json'),JSON.stringify({production:true,fixtureOnly:true,realBankTransfers:false,errors,calls,publicChunks:{count:commonChunks.length,bytes:Buffer.concat(commonChunks).length},screenshots:['bank-locked.png','buyer-handoff.png','owner-usage-only.png','report-peer.png']},null,2))
  console.log(JSON.stringify({passed:true,production:true,fixtureOnly:true,errors:errors.length,requests:calls.length,output}))
} catch (failure) {
  writeFileSync(path.join(output,'failure.json'),JSON.stringify({errors,calls,error:String(failure)},null,2))
  if(browser) for(const [i,context] of browser.contexts().entries()) for(const page of context.pages()) {
    await page.screenshot({path:path.join(output,`failure-${i}.png`),fullPage:true}).catch(()=>{})
    console.error(JSON.stringify({url:page.url(),text:(await page.locator('body').innerText().catch(()=>'' )).slice(-4000)}))
  }
  throw failure
} finally { await browser?.close();server.kill() }
