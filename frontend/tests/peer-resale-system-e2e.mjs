// Genuine BFF/Gateway/services, isolated databases/broker, SePay QR and incoming-webhook fixture.
// Only the first synthetic Admin role is bootstrapped. No financial/business status SQL.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { randomUUID, createHash } from 'node:crypto'

const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
assert.match(config.pg, /^sporthub-real-test-pg-[a-f0-9]+$/)
assert.match(config.payosMockURL, /^http:\/\/127\.0\.0\.1:\d+$/)
assert.ok(Object.values(config.databases).every(name => /^\w+_system_phase12_[a-f0-9]+$/.test(name)))
const require = createRequire(import.meta.url)
const { chromium, request } = require(process.env.SPORTHUB_E2E_PLAYWRIGHT || 'playwright')
const password = 'RealSynthetic123!', actors = {}, contexts = [], checks = [], errors = []
const key = () => ({ 'Idempotency-Key': randomUUID() })
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const pass = name => { checks.push(name); console.log('PASS: ' + name) }
const guest = await request.newContext({ baseURL: config.baseURL })
async function call(actor, method, url, body, expected = 200, headers = {}, extra = {}) {
  const {origin='',...requestOptions}=extra
  const identity = actors[actor], options = { method, headers: { ...headers, ...(identity?.token ? { Authorization: 'Bearer ' + identity.token } : {}) }, ...requestOptions }
  if (body !== undefined) options.data = body
  const response = await (identity?.http || guest).fetch(origin+'/api/v1' + url, options)
  const envelope = await response.json().catch(() => null)
  assert.ok((Array.isArray(expected) ? expected : [expected]).includes(response.status()), `${actor} ${method} ${url}: ${response.status()} ${JSON.stringify(envelope)}`)
  return envelope?.data ?? envelope
}
async function poll(read, predicate, seconds = 60) {
  const deadline = Date.now() + seconds * 1000; let value
  while (Date.now() < deadline) { value = await read(); if (predicate(value)) return value; await sleep(400) }
  throw Error('State did not converge: ' + JSON.stringify(value))
}
async function login(name) { const result = await call(name, 'POST', '/auth/login', { identifier: actors[name].email, password }); actors[name].token = result.accessToken; return result }
async function register(name) {
  const email = `real-${name}-${config.run}@phase12.invalid`.toLowerCase()
  actors[name] = { email, http: await request.newContext({ baseURL: config.baseURL }) }
  const result = await call(name, 'POST', '/auth/register', { fullName: 'Synthetic ' + name, email, password }, 201)
  const code = await poll(async () => JSON.parse(fs.readFileSync(path.join(config.output, 'smtp-codes.json'), 'utf8'))[email], Boolean)
  await call(name, 'POST', '/auth/verify', { challengeId: result.verificationChallengeId, code }); actors[name].id = result.userId; await login(name)
}
let browser, facility, slot, collectionChannel, bankTransaction=100000
const webhookSecret='phase12-sepay-webhook-fixture-0123456789'
async function incoming(qr,amount) {
  const id=++bankTransaction
  const body={id,gateway:'VCB',accountNumber:'000555666777',subAccount:'',transactionDate:new Date().toLocaleString('sv-SE',{timeZone:'Asia/Ho_Chi_Minh'}),code:qr.description,content:'PAY '+qr.description,transferType:'in',transferAmount:amount,referenceCode:'FT-SEPAY-'+id}
  for(let attempt=0;attempt<2;attempt++) {
    const ack=await call('', 'POST', `/payments/providers/sepay/${collectionChannel}/webhook`,body,200,{Authorization:'Apikey '+webhookSecret})
    assert.equal(ack.success,true)
  }
}
const courts = {}, destinations = {}
async function loginPage(actor, width = 1440) {
  const context = await browser.newContext({ locale: 'vi-VN', timezoneId: 'Asia/Ho_Chi_Minh', viewport: { width, height: 1000 } }); contexts.push(context)
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message))
  await page.goto(config.baseURL + '/login'); await page.locator('input[name=identifier]').fill(actors[actor].email); await page.locator('input[name=password]').fill(password)
  await page.getByRole('button', { name: 'Đăng nhập ngay', exact: true }).click(); await page.waitForURL(url => !url.pathname.startsWith('/login'))
  return page
}

const receiptPng=fs.readFileSync(new URL('../../backend/payment-service/src/test/resources/peer-qr/sepay-vcb-synthetic.png',import.meta.url))
async function quote(actor,court,range=slot) {return call(actor,'POST','/bookings/quote',{courtId:courts[court].id,...range})}
async function hold(actor,court) {
  const priced=await quote(actor,court)
  return call(actor,'POST','/bookings/holds',{courtId:courts[court].id,...slot,expectedAmount:Number(priced.amount),quoteHash:priced.quoteHash},201,key())
}
async function bank(actor,account='000123456789',expected=0) {
  const result=await call(actor,'POST','/payments/bank-destinations',{bankBin:'970436',accountNumber:account,accountName:'SYNTHETIC '+actor,consentReference:randomUUID(),expectedProfileVersion:expected},201,key())
  destinations[actor]=result.id;return result
}
async function paidBook(actor,court,partial=false) {
  const held=await hold(actor,court), booking=await call(actor,'POST','/bookings',{holdId:held.id},201,key())
  const payment=await call(actor,'POST','/payments',{bookingId:booking.id},201,key())
  const qr=await poll(()=>call(actor,'GET',`/payments/${payment.id}/qr`),value=>value.state==='READY')
  assert.equal(qr.accountNumber,'000555666777')
  if(partial) {
    await incoming(qr,40000)
    await poll(()=>call(actor,'GET',`/payments/${payment.id}/qr`),value=>value.state==='PARTIAL')
    const unpaid=await call(actor,'GET',`/bookings/${booking.id}`)
    assert.equal(unpaid.booking.status,'PENDING');assert.equal(unpaid.checkinToken,null)
  }
  assert.equal(payment.provider,'SEPAY')
  await incoming(qr,partial?80000:Number(payment.amount))
  const detail=await poll(()=>call(actor,'GET',`/bookings/${booking.id}`),value=>value.booking.status==='CONFIRMED')
  return {booking:detail.booking,detail,payment}
}
async function publish(actor,booking,price=80000) {
  const input={bookingId:booking.id,price,deadline:new Date(Date.parse(booking.startsAt)-60000).toISOString(),bankDestinationRevisionId:destinations[actor]}
  const terms=await call(actor,'POST','/transfers/quote',input)
  assert.equal(terms.protocol,'PEER_RESALE_V1');assert.equal(terms.bankSharingConsent,'BUYER_AND_SEPAY_QR_V1')
  assert.equal(Object.hasOwn(terms,'feeBps'),false);assert.equal(Object.hasOwn(terms,'quotedSellerNetAmount'),false)
  const listing=await call(actor,'POST','/transfers',{...input,acceptedTermsHash:terms.termsHash},201,key())
  await poll(()=>call(actor,'GET',`/transfers/${listing.id}`),value=>value.available)
  return {listing,terms}
}
async function acquire(actor,sale,requestKey=key()) {return call(actor,'POST',`/transfers/${sale.listing.id}/acquisitions`,{acceptedTermsHash:sale.terms.termsHash},201,requestKey)}
async function attempt(actor,id) {return call(actor,'GET',`/transfers/acquisitions/${id}/peer`)}
async function evidence(actor,id) {return call(actor,'POST',`/transfers/acquisitions/${id}/evidence`,undefined,200,key(),{multipart:{file:{name:'synthetic-receipt.png',mimeType:'image/png',buffer:receiptPng}}})}
async function declare(actor,id) {
  const current=await attempt(actor,id),photo=await evidence(actor,id)
  assert.equal(photo.state,'READY')
  const body={version:current.version,termsHash:current.termsHash,bankTransactionReference:'SYNTH-'+randomUUID(),evidenceIds:[photo.id]}
  await call(actor,'POST',`/transfers/acquisitions/${id}/declare-payment`,{...body,amount:1},400,key())
  const value=await call(actor,'POST',`/transfers/acquisitions/${id}/declare-payment`,body,200,key())
  assert.equal(value.phase,'PAYMENT_DECLARED');return {value,photo}
}
async function confirm(actor,id) {
  const current=await attempt(actor,id),body={version:current.version,termsHash:current.termsHash},requestKey=key()
  const result=await call(actor,'POST',`/transfers/acquisitions/${id}/confirm-receipt`,body,200,requestKey)
  assert.equal(result.phase,'COMPLETED')
  assert.equal((await call(actor,'POST',`/transfers/acquisitions/${id}/confirm-receipt`,body,200,requestKey)).phase,'COMPLETED')
}
function sql(service,query) {return execFileSync('docker',['exec',config.pg,'psql','-U',config.pgUser,'-d',config.databases[service],'-At','-v','ON_ERROR_STOP=1','-c',query],{windowsHide:true,encoding:'utf8'}).trim()}

try {
  for (const actor of ['admin', 'owner', 'staff', 'a', 'b', 'c', 'race', 'refundBuyer','clockSeller','timeoutB','noshowB','presentB','hoard','regrab','rate','bankHost','bankMember','counterCustomer']) await register(actor)
  execFileSync('docker', ['exec', config.pg, 'psql', '-U', config.pgUser, '-d', config.databases.identity, '-v', 'ON_ERROR_STOP=1', '-c', `INSERT INTO user_roles(user_id,role) VALUES('${actors.admin.id}','ADMIN')`], { windowsHide: true }); await login('admin')
  const category = await call('admin', 'POST', '/admin/sport-categories', { name: 'REAL fixture ' + config.run, active: true }, 201)
  const application = await call('owner', 'POST', '/owner-applications', {
    legal: { representativeName: 'Synthetic representative', identityNumber: 'SYNTH-' + config.run, businessName: 'Synthetic REAL facility', bankName: 'Synthetic Bank', bankAccountHolder: 'Synthetic representative', bankAccountNumber: '1234567890' },
    facility: { name: 'REAL fixture ' + config.run, phone: '+84901234567', addressLine: 'Synthetic address', provinceCode: '01', province: 'Ha Noi', wardCode: '00004', ward: 'Ba Dinh', timezone: 'Asia/Ho_Chi_Minh', latitude: 21.04, longitude: 105.84, amenities: ['Parking'], contactEmail: actors.owner.email },
  }, 201); facility = application.facilityId
  for (const name of ['main', 'rights', 'guest', 'linked', 'group', 'technical', 'fault', 'broker', 'manual', 'race']) courts[name] = await call('owner', 'POST', `/owner/facilities/${facility}/courts`, { code: name, name: 'Sân kiểm thử ' + name, sportCategoryId: category.id, enabled: true }, 201)
  await call('owner', 'PUT', `/schedules/facilities/${facility}/hours`, { intervals: Array.from({ length: 7 }, (_, index) => ({ dayOfWeek: index + 1, opensAt: '00:00', closesAt: '23:30', slotMinutes: 30 })) })
  const today = new Date(Date.now() + 7 * 3600000).toISOString().slice(0, 10)
  for (let dayOfWeek = 1; dayOfWeek <= 7; dayOfWeek++) await call('owner', 'POST', `/schedules/facilities/${facility}/pricing`, { dayOfWeek, startsAt: '00:00', endsAt: '23:30', pricingUnit: 'HOURLY', pricePerHour: 120000, pricePerSlot: null, priority: 0, label: 'Synthetic REAL price', effectiveFrom: today, currency: 'VND' }, 201)
  await call('owner','PUT',`/schedules/facilities/${facility}/booking-policy`,{enabled:true,version:0,selectionStepMinutes:30,minimumMinutes:30,maximumMinutes:null},200,key())
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB3sAAAAASUVORK5CYII=', 'base64')
  for (const name of ['identity.png', 'location.png']) await call('owner', 'POST', `/owner/facilities/${facility}/documents`, undefined, 200, {}, { multipart: { file: { name, mimeType: 'image/png', buffer: png } } })
  await call('owner', 'POST', `/owner/facilities/${facility}/images`, undefined, 201, {}, { multipart: { file: { name: 'facility.png', mimeType: 'image/png', buffer: png } } })
  await call('owner', 'POST', `/owner-applications/${application.id}/submit`)
  await poll(() => call('admin', 'GET', `/admin/owner-applications/${application.id}`), value => value.application.state === 'PENDING_APPROVAL')
  await call('admin', 'POST', `/admin/owner-applications/${application.id}/decision`, { action: 'APPROVE', reason: 'Complete synthetic dossier; no platform fee', commissionPercent: 0 })
  await poll(() => call('admin', 'GET', `/admin/owner-applications/${application.id}`), value => value.application.state === 'APPROVED'); await login('owner')
  assert.equal((await call('guest', 'GET', `/facilities/${facility}`)).status, 'ACTIVE')
  pass('Owner onboarding and facility activation use genuine APIs and saga; no business status SQL')
  const permissions = ['BOOKING_READ', 'BOOKING_CREATE_COUNTER', 'BOOKING_PAYMENT_COUNTER', 'BOOKING_CHECK_IN', 'BOOKING_REFUND', 'TRANSFER_PAYOUT']
  const profile = await call('owner', 'POST', `/staff-profiles/facilities/${facility}`, { fullName: 'Synthetic operator', email: actors.staff.email, active: true })
  const invitation = await call('owner', 'POST', `/staff-profiles/facilities/${facility}/${profile.id}/invitations`, { identifier: actors.staff.email, permissions }); await call('staff', 'POST', `/users/me/staff-invitations/${invitation.id}/accept`)
  await call('owner', 'POST', `/staff-bindings/facilities/${facility}`, { identifier: actors.staff.email, permissions }); await login('staff')

  const tomorrow=new Date(Date.parse(today+'T00:00:00Z')+86400000).toISOString().slice(0,10)
  slot={startsAt:tomorrow+'T10:00:00+07:00',endsAt:tomorrow+'T11:00:00+07:00'}
  for (const [startsAt,endsAt,pricePerHour] of [['15:00','16:00',100000],['16:00','17:00',200000]])
    await call('owner','POST',`/schedules/facilities/${facility}/pricing`,{courtId:courts.fault.id,specificDate:tomorrow,startsAt,endsAt,pricingUnit:'HOURLY',pricePerHour,pricePerSlot:null,priority:1,label:'Minute integration fixture',effectiveFrom:today,currency:'VND'},201)
  const flexible=await quote('a','fault',{startsAt:tomorrow+'T15:30:00+07:00',endsAt:tomorrow+'T16:30:00+07:00'})
  assert.equal(Number(flexible.amount),150000)
  pass('Schedule and Booking HTTP quote integrates 30 minutes at 100k/h plus 30 at 200k/h to 150k')
  assert.equal(await call('owner','GET',`/payments/facilities/${facility}/sepay-channel`,undefined,204),null)
  const browserChannel=process.env.SPORTHUB_E2E_BROWSER_CHANNEL||process.env.SPORTHUB_E2E_BROWSER
  browser=await chromium.launch({headless:true,...(browserChannel?{channel:browserChannel}:{})})
  const setupPage=await loginPage('owner')
  await setupPage.goto(config.baseURL+`/owner/facilities/${facility}/payment-channel`)
  await setupPage.getByText('Cơ sở chưa có tài khoản nhận tiền.',{exact:false}).waitFor()
  await setupPage.locator('select[name=bankBin]').selectOption('970436')
  await setupPage.locator('input[name=accountNumber]').fill('000555666777')
  await setupPage.locator('input[name=accountName]').fill('SYNTHETIC FACILITY')
  await setupPage.locator('input[name=webhookApiKey]').fill(webhookSecret)
  const channelButton=setupPage.getByRole('button',{name:'Lưu kênh SePay',exact:true})
  assert.equal(await channelButton.getAttribute('type'),'submit')
  const [channelResponse]=await Promise.all([
    setupPage.waitForResponse(reply=>reply.request().method()==='PUT'&&new URL(reply.url()).pathname.endsWith('/sepay-channel')),
    channelButton.click(),
  ]);assert.equal(channelResponse.status(),200)
  const ready=(await channelResponse.json()).data
  collectionChannel=ready.channelId
  assert.equal(ready.status,'READY');assert.ok(!JSON.stringify(ready).match(/webhookApiKey|checksumKey|ciphertext/))
  await setupPage.getByText('Đã cấu hình SePay',{exact:false}).waitFor()
  await setupPage.screenshot({path:path.join(config.output,'owner-first-sepay-setup.png'),fullPage:true})
  pass('Owner configures the first SePay channel through the production browser form after a valid empty GET')
  await call('owner','PUT',`/owner/facilities/${facility}/transfer-policy`,{enabled:true,minLeadSeconds:0})
  assert.equal((await call('a','GET','/payments/bank-profile')).initialized,false)
  const main=await paidBook('a','main',true)
  const firstProfile=await call('a','GET','/payments/bank-profile');assert.equal(firstProfile.canRegisterFirst,true);assert.equal(firstProfile.initialized,false)
  const sellerSettings=await loginPage('a')
  await sellerSettings.goto(config.baseURL+'/customer/profile/bank')
  await sellerSettings.getByRole('heading',{name:'Ngân hàng nhận tiền chuyển nhượng',exact:true}).waitFor()
  await sellerSettings.locator('select[name=bankBin]').selectOption('970436')
  await sellerSettings.locator('input[name=accountNumber]').fill('000123456789')
  await sellerSettings.locator('input[name=accountName]').fill('SYNTHETIC SELLER')
  await sellerSettings.locator('input[type=checkbox]').check()
  const [bankResponse]=await Promise.all([
    sellerSettings.waitForResponse(reply=>reply.request().method()==='POST'&&new URL(reply.url()).pathname.endsWith('/bank-destinations')),
    sellerSettings.getByRole('button',{name:'Lưu tài khoản nhận',exact:true}).click(),
  ]);assert.equal(bankResponse.status(),201)
  destinations.a=(await bankResponse.json()).data.id
  await sellerSettings.locator(`option[value="${destinations.a}"]`).waitFor({state:'attached'})
  await sellerSettings.screenshot({path:path.join(config.output,'customer-first-resale-bank.png'),fullPage:true})
  pass('Customer books and pays with no personal bank, then registers the first resale bank through the production settings form')
  await bank('c','000888999222');await bank('refundBuyer','000888999333')
  await call('a','POST','/payments/bank-destinations',{bankBin:'970436',accountNumber:'999999',accountName:'A',consentReference:randomUUID(),expectedProfileVersion:1},409,key())
  assert.equal((await call('a','GET','/payments/bank-profile')).activeRevisionId,destinations.a)
  pass('REAL partial payment has no usage; full original bank proof enables booking; first bank registration is allowed but replacement locks')
  const sale=await publish('a',main.booking)
  const nativeQuote=await quote('race','rights')
  const raced=await Promise.all([
    call('race','POST','/bookings/holds',{courtId:courts.rights.id,...slot,expectedAmount:Number(nativeQuote.amount),quoteHash:nativeQuote.quoteHash},[201,409],key()),
    call('race','POST',`/transfers/${sale.listing.id}/acquisitions`,{acceptedTermsHash:sale.terms.termsHash},[201,409],key()),
  ])
  const winners=raced.filter(value=>value?.id);assert.equal(winners.length,1)
  if(winners[0].listingId) {
    const held=await attempt('race',winners[0].id)
    await call('race','POST',`/transfers/acquisitions/${held.id}/release`,{version:held.version,termsHash:held.termsHash},200,key())
  } else await call('race','DELETE',`/bookings/holds/${winners[0].id}`,undefined,204)
  await poll(()=>call('b','GET',`/transfers/${sale.listing.id}`),value=>value.available)
  pass('Concurrent Booking and Transfer HTTP calls on different courts grant one shared user claim; loser leaves listing usable')
  assert.equal((await call('b','GET','/payments/bank-profile')).initialized,false)
  const purchaseKey=key(),purchase=await acquire('b',sale,purchaseKey),replay=await acquire('b',sale,purchaseKey)
  assert.equal(replay.id,purchase.id);assert.equal(replay.expiresAt,purchase.expiresAt)
  const instruction=await call('b','GET',`/payments/peer-acquisitions/${purchase.id}/instruction`)
  assert.equal(instruction.memo,'SH1');assert.equal(instruction.amount,'80000');assert.equal(instruction.accountVerification,'SELF_DECLARED')
  assert.equal(instruction.confirmationMethod,'NONE');assert.ok(!JSON.stringify(instruction).includes('000123456789'))
  const image=await actors.b.http.get(instruction.qrUrl,{headers:{Authorization:'Bearer '+actors.b.token}})
  assert.equal(image.status(),200);assert.deepEqual(await image.body(),receiptPng);assert.match(image.headers()['cache-control'],/no-store/)
  await call('c','GET',`/payments/peer-acquisitions/${purchase.id}/instruction`,undefined,403)
  await call('c','GET',`/transfers/acquisitions/${purchase.id}/peer`,undefined,403)
  const occupied=await call('b','POST','/bookings/holds',{courtId:courts.rights.id,...slot,expectedAmount:Number(nativeQuote.amount),quoteHash:nativeQuote.quoteHash},409,key())
  assert.equal(occupied.code,'HOLD_QUOTA_EXCEEDED')
  const message=await call('b','POST',`/transfers/conversations/${purchase.conversationId}/messages`,{text:'<img src=x onerror=alert(1)> Trao đổi thử'},200,key())
  assert.equal((await call('a','GET',`/transfers/conversations/${purchase.conversationId}/messages?after=0&size=50`))[0].text,message.text)
  const declared=await declare('b',purchase.id)
  assert.equal((await call('a','GET',`/bookings/${main.booking.id}`)).checkinToken,null)
  await call('b','POST',`/transfers/acquisitions/${purchase.id}/confirm-receipt`,{version:declared.value.version,termsHash:declared.value.termsHash},403,key())
  await confirm('a',purchase.id)
  const buyerDetail=await call('b','GET',`/bookings/${main.booking.id}`)
  assert.equal(buyerDetail.booking.currentHolderId,actors.b.id);assert.equal(buyerDetail.resaleConsumed,true);assert.ok(buyerDetail.checkinToken)
  assert.notEqual(buyerDetail.booking.bookingCode,main.booking.bookingCode)
  await call('staff','POST',`/bookings/facility/${facility}/check-in-by-code`,{bookingCode:main.booking.bookingCode},404)
  assert.equal((await call('b','GET','/payments/bank-profile')).initialized,false)
  await bank('b','000888999111')
  const resaleRejected=await call('b','POST','/transfers/quote',{bookingId:main.booking.id,price:70000,deadline:sale.terms.deadline,bankDestinationRevisionId:destinations.b},409)
  assert.match(resaleRejected.message,/chỉ được chuyển nhượng thành công một lần/)
  const buyerAfterResale=await call('b','GET',`/bookings/${main.booking.id}`)
  assert.equal(buyerAfterResale.booking.currentHolderId,actors.b.id)
  assert.equal(buyerAfterResale.booking.version,buyerDetail.booking.version)
  assert.equal(sql('booking',`SELECT count(*) FROM peer_booking_handoffs WHERE booking_id='${main.booking.id}'`),'1')
  assert.equal(sql('payment',`SELECT count(*) FROM payment_orders WHERE booking_id='${main.booking.id}' AND purpose='TRANSFER'`),'0')
  assert.equal(sql('booking',`SELECT count(*) FROM facility_disbursement_obligations WHERE booking_id='${main.booking.id}'`),'0')
  pass('Private QR reaches A; one handoff rotates rights once; B→C is rejected without changing holder/version or creating fee/payout/payment orders')
  const caseBooking=await paidBook('a','rights'),caseSale=await publish('a',caseBooking.booking),casePurchase=await acquire('c',caseSale)
  const caseDeclaration=await declare('c',casePurchase.id)
  await call('a','POST',`/transfers/acquisitions/${casePurchase.id}/deny-receipt`,{version:caseDeclaration.value.version,termsHash:caseDeclaration.value.termsHash},200,key())
  const disputed=await attempt('c',casePurchase.id);assert.equal(disputed.phase,'DISPUTED')
  const resolution={version:disputed.version,outcome:'SELLER_RETAINS',reason:'Synthetic evidence review decides usage only',evidenceIds:[caseDeclaration.photo.id]}
  await call('staff','GET',`/transfers/facilities/${facility}/cases/${casePurchase.id}`,undefined,403)
  await call('staff','POST',`/transfers/facilities/${facility}/cases/${casePurchase.id}/resolve`,resolution,403,key())
  await call('owner','POST',`/transfers/facilities/${facility}/cases/${casePurchase.id}/resolve`,{...resolution,amount:1},400,key())
  await call('owner','POST',`/transfers/facilities/${facility}/cases/${casePurchase.id}/resolve`,resolution,200,key())
  const moneyCase=await call('c','GET',`/transfers/acquisitions/${casePurchase.id}/money`)
  assert.equal(moneyCase.state,'OPEN');assert.equal((await call('c','GET','/payments/bank-profile')).canChange,true)
  assert.equal(sql('booking',`SELECT count(*) FROM facility_disbursement_obligations WHERE booking_id='${caseBooking.booking.id}'`),'0')
  const sellerPhoto=await evidence('a',casePurchase.id)
  await call('a','POST',`/transfers/acquisitions/${casePurchase.id}/returns`,{termsHash:disputed.termsHash,amount:40000,bankTransactionReference:'DIRECT-'+config.run,evidenceIds:[sellerPhoto.id]},200,key())
  const direct=await call('c','GET',`/transfers/acquisitions/${casePurchase.id}/money`)
  assert.equal((await call('c','GET','/payments/bank-profile')).canChange,false)
  await call('c','POST',`/transfers/acquisitions/${casePurchase.id}/returns/${direct.returns[0].id}/acknowledge`,{version:direct.returns[0].version,termsHash:disputed.termsHash},200,key())
  assert.equal((await call('c','GET','/payments/bank-profile')).canChange,true)
  assert.equal((await call('c','GET',`/transfers/acquisitions/${casePurchase.id}/money`)).state,'OPEN')
  pass('Owner usage-only decision removes dispute bank lock while OPEN money remains; a pending A return locks separately until C acknowledges')
  const refundable=await paidBook('a','technical'),refundSale=await publish('a',refundable.booking,100000),refundPurchase=await acquire('refundBuyer',refundSale)
  await declare('refundBuyer',refundPurchase.id);await confirm('a',refundPurchase.id)
  for (const actor of ['refundBuyer','owner','staff']) {
    await call(actor,'POST',`/bookings/${refundable.booking.id}/cancellation-quote`,undefined,404)
    await call(actor,'POST',`/bookings/${refundable.booking.id}/cancel-with-policy`,{quoteId:randomUUID(),reason:'Buyer cancellation'},404,key())
  }
  assert.equal(sql('booking',`SELECT count(*) FROM facility_disbursement_obligations WHERE booking_id='${refundable.booking.id}'`),'0')
  const report=await call('owner','GET',`/bookings/reports/facility/${facility}/dashboard?fromDate=${today}&toDate=${today}&mode=REAL&size=25`)
  assert.equal(report.summary.money.courtCollected,'360000');assert.equal(report.summary.money.bankCollected,'360000')
  assert.equal(report.summary.money.policyRefunded,'0');assert.equal(report.summary.money.afterRefund,'360000')
  assert.equal(report.summary.money.transferFees,'0');assert.equal(report.summary.money.sellerDue,'0');assert.equal(report.peer.openMoneyCases,1)
  pass('Resold bookings reject new refunds; R remains facility cash while P and A returns stay separate')
  const buyerPage=await loginPage('b',390)
  await buyerPage.goto(config.baseURL+`/customer/bookings/${main.booking.id}`)
  await buyerPage.getByText('Lượt đặt đã chuyển nhượng một lần, không thể đăng bán tiếp.').waitFor()
  assert.equal(await buyerPage.getByRole('link',{name:'Đăng tin chuyển nhượng trực tiếp',exact:true}).count(),0)
  await buyerPage.screenshot({path:path.join(config.output,'peer-buyer-real-services.png'),fullPage:true})
  const ownerPage=await loginPage('owner')
  await ownerPage.goto(config.baseURL+`/owner/facilities/${facility}/reports`)
  await ownerPage.getByRole('heading',{name:'Chuyển nhượng trực tiếp B → A',exact:true}).waitFor()
  await ownerPage.screenshot({path:path.join(config.output,'peer-report-real-services.png'),fullPage:true})
  assert.deepEqual(errors,[])
  if(config.comparisonBaseURL) {
    const {peerReadPerformance}=await import('./support/peer-read-performance.mjs')
    await peerReadPerformance({browser,config,actors,password,facility,court:main.booking.courtId,quoteCourt:courts.fault.id,slot,booking:main.booking.id,call,pass})
  }
  if(!config.performanceOnly) {
  const {peerSystemFaults}=await import('./support/peer-system-faults.mjs')
  await peerSystemFaults({config,call,actors,bank,paidBook,publish,acquire,attempt,declare,evidence,poll,key,slot,facility,main,buyerDetail,pass})
  const {peerSystemAdmission}=await import('./support/peer-system-admission.mjs')
  await peerSystemAdmission({config,call,actors,quote,key,slot,courts,pass})
  const {peerSystemBanks}=await import('./support/peer-system-banks.mjs')
  await peerSystemBanks({config,call,actors,bank,quote,key,slot,courts,facility,poll,pass})
  }
  const testPaths=['frontend/tests/peer-resale-system-e2e.mjs','frontend/tests/support/peer-system-faults.mjs','frontend/tests/support/peer-system-admission.mjs','frontend/tests/support/peer-system-banks.mjs','frontend/tests/support/peer-read-performance.mjs','frontend/tests/support/peer-content-timing.mjs','scripts/system-e2e.py','scripts/peer-resale-system-e2e.py','scripts/real-payments-system-e2e.py','scripts/test/payos-native-mock.mjs','scripts/test/FixtureClockConfig.java']
  const testSources=Object.fromEntries(testPaths.map(file=>[file,createHash('sha256').update(fs.readFileSync(new URL('../../'+file,import.meta.url))).digest('hex')]))
  const result={passed:true,scope:config.performanceOnly?'CORE_AND_PERFORMANCE':'FULL_PEER',fixtureOnly:false,realServices:true,realBankTransfers:false,testSources,checks,errors,facility,bookings:[main.booking.id,caseBooking.booking.id,refundable.booking.id],acquisitions:[purchase.id,casePurchase.id,refundPurchase.id],report}
  fs.writeFileSync(path.join(config.output,'result.json'),JSON.stringify(result,null,2))
  console.log(JSON.stringify({passed:true,checks:checks.length,realServices:true,realBankTransfers:false}))
} finally {
  await browser?.close();await Promise.all(contexts.map(context=>context.close()));await guest.dispose()
  await Promise.all(Object.values(actors).map(actor=>actor.http.dispose()))
}
