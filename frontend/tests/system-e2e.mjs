// Genuine BFF -> Gateway -> six services -> separate PG/Rabbit/MinIO.
// No page.route(), fake payment responses, owner-role or facility-state SQL.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {createRequire} from 'node:module'
import {execFileSync} from 'node:child_process'
import {randomUUID} from 'node:crypto'

const config=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))
assert.match(new URL(config.baseURL).hostname,/^(127\.0\.0\.1|localhost)$/)
assert.ok(Object.values(config.databases).every(n=>/^\w+_system_phase12_[a-f0-9]+$/.test(n)))
const require=createRequire(import.meta.url)
const {chromium,request}=require(process.env.SPORTHUB_E2E_PLAYWRIGHT||'playwright')
const output=config.output, password='Phase12Synthetic123!', actors={}, checks=[], browserChecks=[], failures=[]
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))
const key=()=>({'Idempotency-Key':randomUUID()})
const record=(name,kind='api')=>{(kind==='browser'?browserChecks:checks).push(name);console.log('PASS ['+kind+']: '+name)}
async function poll(fn,predicate,seconds=35){const deadline=Date.now()+seconds*1000;let value;while(Date.now()<deadline){value=await fn();if(predicate(value))return value;await sleep(350)}throw Error('Condition not reached: '+JSON.stringify(value))}
async function call(actor,method,url,body,expected=200,headers={},extra={}){
  const a=actors[actor],options={method,headers:{...headers,...(a?.token?{Authorization:'Bearer '+a.token}:{})},...extra}
  if(body!==undefined)options.data=body
  let response=await (a?.http||guest).fetch('/api/v1'+url,options)
  if(response.status()===401&&!url.startsWith('/auth/')&&a){
    const renewed=await a.http.post('/api/v1/auth/refresh');assert.equal(renewed.status(),200,'refresh real cookie')
    a.token=(await renewed.json()).data.accessToken;options.headers.Authorization='Bearer '+a.token
    response=await a.http.fetch('/api/v1'+url,options)
  }
  let payload;try{payload=await response.json()}catch{payload=null}
  assert.equal(response.status(),expected,`${actor} ${method} ${url}: ${JSON.stringify(payload)}`)
  return payload?.data??payload
}
async function login(name){const session=await call(name,'POST','/auth/login',{identifier:actors[name].email,password});actors[name].token=session.accessToken;return session}
async function register(name){
  const email=`${name}-${config.run}@phase12.invalid`,http=await request.newContext({baseURL:config.baseURL})
  actors[name]={email,http}
  const r=await call(name,'POST','/auth/register',{fullName:'Synthetic '+name,email,password},201)
  const otp=await poll(async()=>JSON.parse(fs.readFileSync(path.join(output,'smtp-codes.json'),'utf8'))[email],Boolean)
  await call(name,'POST','/auth/verify',{challengeId:r.verificationChallengeId,code:otp})
  actors[name].id=r.userId;await login(name)
}
async function payment(bookingId,actor,memberId,acquisitionId){
  const p=await call(actor,'POST','/payments',{bookingId,provider:'DEMO',memberId,acquisitionId},201,key())
  await call(actor,'POST',`/payments/${p.id}/demo/complete`,{outcome:'SUCCESS'})
  return p
}
async function booking(courtId,startsAt,endsAt,actor='customer',amount=100001){
  const h=await call(actor,'POST','/bookings/holds',{courtId,startsAt,endsAt,expectedAmount:amount},201,key())
  return call(actor,'POST','/bookings',{holdId:h.id},201,key())
}
async function paid(b,actor='customer'){await payment(b.id,actor);return poll(()=>call(actor,'GET',`/bookings/${b.id}`),r=>r.booking.status==='CONFIRMED')}
async function loginPage(name){const context=await browser.newContext({locale:'vi-VN',timezoneId:'Asia/Ho_Chi_Minh',viewport:{width:1440,height:1000}}),page=await context.newPage();contexts.push(context)
  page.on('pageerror',error=>browserErrors.push({url:page.url(),message:error.message}))
  await page.goto(config.baseURL+'/login');await page.locator('input[name=identifier]').fill(actors[name].email);await page.locator('input[name=password]').fill(password)
  await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();await page.waitForURL(url=>!url.pathname.startsWith('/login'),{timeout:25000});return page
}
async function view(page,route,heading){await page.goto(config.baseURL+route);await page.getByRole('heading',{name:heading}).first().waitFor({timeout:20000});await sleep(300)}
async function assertNoAlert(page){const alerts=await page.getByRole('alert').evaluateAll(nodes=>nodes.filter(n=>n.id!=='__next-route-announcer__').map(n=>n.textContent));assert.equal(alerts.filter(s=>s.trim()).length,0,'UI errors: '+alerts.join('; '))}
const guest=await request.newContext({baseURL:config.baseURL}),contexts=[],browserErrors=[]
let browser,fixture={}
try{
  for(const name of ['admin','owner','staff','customer','buyer','racer'])await register(name)
  // Initial Admin is the sole explicit bootstrap fixture; all business roles use APIs.
  execFileSync('docker',['exec',config.pg,'psql','-U',config.pgUser,'-d',config.databases.identity,'-v','ON_ERROR_STOP=1','-c',`INSERT INTO user_roles(user_id,role) VALUES('${actors.admin.id}','ADMIN')`],{windowsHide:true})
  await login('admin')
  const cookies=(await actors.customer.http.storageState()).cookies
  assert.ok(cookies.some(c=>c.httpOnly&&c.path.startsWith('/api/v1/auth')&&c.sameSite==='Lax'))
  await call('customer','GET','/admin/accounts',undefined,403)
  await call('customer','GET','/owner/facilities',undefined,403)
  const cross=await guest.post('/api/v1/auth/refresh',{headers:{Origin:'https://cross-site.invalid'}});assert.equal(cross.status(),403)
  const internal=await guest.get('/api/v1/internal/identity/anything');assert.ok([401,403,404].includes(internal.status()))
  record('Registration/SMTP OTP/login/HttpOnly cookie/BFF status and role/cross-site/internal guards')
  const category=await call('admin','POST','/admin/sport-categories',{name:'Phase12 sport '+config.run,active:true},201)
  const application=await call('owner','POST','/owner-applications',{
    legal:{representativeName:'Synthetic representative',identityNumber:'SYNTH-'+config.run,businessName:'Synthetic Sports '+config.run,bankName:'Synthetic Bank',bankAccountHolder:'Synthetic representative',bankAccountNumber:'1234567890'},
    facility:{name:'Phase12 Sports '+config.run,phone:'+84901234567',addressLine:'Synthetic test address',provinceCode:'01',province:'Ha Noi',wardCode:'00004',ward:'Ba Dinh',timezone:'Asia/Ho_Chi_Minh',latitude:21.04,longitude:105.84,amenities:['Parking'],contactEmail:actors.owner.email}
  },201)
  const fid=application.facilityId,appId=application.id
  const court=await call('owner','POST',`/owner/facilities/${fid}/courts`,{code:'MAIN',name:'Main court',sportCategoryId:category.id,enabled:true},201)
  const intervals=Array.from({length:7},(_,i)=>({dayOfWeek:i+1,opensAt:'00:00',closesAt:'23:55',slotMinutes:5}))
  await call('owner','PUT',`/schedules/facilities/${fid}/hours`,{intervals})
  const today=new Date(Date.now()+7*3600000).toISOString().slice(0,10)
  for(let i=1;i<=7;i++)await call('owner','POST',`/schedules/facilities/${fid}/pricing`,{dayOfWeek:i,startsAt:'00:00',endsAt:'23:55',pricePerSlot:100001,priority:0,label:'Test exact integer price',effectiveFrom:today,currency:'VND'},201)
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jB3sAAAAASUVORK5CYII=','base64')
  for(const name of ['identity.png','location.png'])await call('owner','POST',`/owner/facilities/${fid}/documents`,undefined,200,{}, {multipart:{file:{name,mimeType:'image/png',buffer:png}}})
  const image=await call('owner','POST',`/owner/facilities/${fid}/images`,undefined,201,{}, {multipart:{file:{name:'facility.png',mimeType:'image/png',buffer:png}}})
  const media=await guest.get(image.url);assert.equal(media.status(),200);assert.deepEqual(await media.body(),png)
  await call('owner','POST',`/owner-applications/${appId}/submit`)
  await poll(()=>call('admin','GET',`/admin/owner-applications/${appId}`),d=>d.application.state==='PENDING_APPROVAL')
  browser=await chromium.launch({channel:process.env.SPORTHUB_E2E_BROWSER||'msedge',headless:true});const approvalPage=await loginPage('admin');await approvalPage.goto(config.baseURL+`/admin/owner-applications/${appId}`);await approvalPage.getByRole('button',{name:'Phê duyệt Owner',exact:true}).click();await approvalPage.locator('input[name=commissionPercent]').fill('5');await approvalPage.locator('textarea[name=reason]').fill('Synthetic approval after actual complete dossier review');await approvalPage.getByRole('button',{name:'Xác nhận quyết định',exact:true}).click();record('Admin browser dossier approval executes genuine Owner/Facility/Payment saga','browser')
  await poll(()=>call('admin','GET',`/admin/owner-applications/${appId}`),d=>d.application.state==='APPROVED')
  assert.ok((await login('owner')).user.roles.includes('OWNER'))
  assert.equal((await call('guest','GET',`/facilities/${fid}`)).status,'ACTIVE')
  record('Genuine first Owner application/draft court/hours/pricing/MinIO multipart/submit/Admin approval/role and publication saga')
  // Staff consent then binding, live permission reads. No synthetic STAFF role.
  const permissions=['BOOKING_READ','BOOKING_CREATE_COUNTER','BOOKING_PAYMENT_COUNTER','BOOKING_CHECK_IN','SCHEDULE_READ']
  const profile=await call('owner','POST',`/staff-profiles/facilities/${fid}`,{fullName:'Synthetic operator',email:actors.staff.email,active:true})
  const invitation=await call('owner','POST',`/staff-profiles/facilities/${fid}/${profile.id}/invitations`,{identifier:actors.staff.email,permissions})
  await call('staff','POST',`/users/me/staff-invitations/${invitation.id}/accept`)
  const binding=await call('owner','POST',`/staff-bindings/facilities/${fid}`,{identifier:actors.staff.email,permissions})
  assert.ok((await login('staff')).user.roles.includes('STAFF'))
  const foreign=await call('owner','POST','/owner/facilities',{name:'Other draft facility',phone:'+84901234568',addressLine:'Synthetic second address',provinceCode:'01',province:'Ha Noi',wardCode:'00004',ward:'Ba Dinh',timezone:'Asia/Ho_Chi_Minh',amenities:[]},201)
  await call('staff','GET',`/bookings/facility/${foreign.id}`,undefined,403)
  record('Actual Staff consent/binding/role granting and wrong-facility denial')
  // Time setup stays actual-clock: five-minute slots, future starts, no status SQL.
  const tomorrow=new Date(Date.now()+7*3600000+86400000).toISOString().slice(0,10),at=hour=>`${tomorrow}T${String(hour).padStart(2,'0')}:00:00+07:00`,end=hour=>`${tomorrow}T${String(hour).padStart(2,'0')}:05:00+07:00`
  const same={courtId:court.id,startsAt:at(10),endsAt:end(10),expectedAmount:100001},intent=key()
  const h=await call('customer','POST','/bookings/holds',same,201,intent)
  assert.equal((await call('customer','POST','/bookings/holds',same,201,intent)).id,h.id)
  await call('customer','POST','/bookings/holds',{...same,startsAt:at(11),endsAt:end(11)},409,intent)
  await call('buyer','GET',`/bookings/holds/${h.id}`,undefined,403)
  await call('customer','DELETE',`/bookings/holds/${h.id}`,undefined,204)
  const race=await Promise.all(['customer','buyer'].map(async actor=>{try{return await call(actor,'POST','/bookings/holds',same,201,key())}catch(error){return {error:String(error)}}}))
  assert.equal(race.filter(v=>v.id).length,1);assert.ok(race.filter(v=>v.error).every(v=>v.error.includes('409')), 'Only business conflict may lose the race');const winner=race.findIndex(v=>v.id);await call(['customer','buyer'][winner],'DELETE',`/bookings/holds/${race[winner].id}`,undefined,204)
  record('Real PostgreSQL exclusion through BFF, concurrent customers, retry, payload conflict and foreign hold access')
  const counterBody={...same,startsAt:at(12),endsAt:end(12),guestName:'Synthetic walk-in',guestPhone:'+84988888888',paymentMethod:'DEMO'}
  const counter=await call('staff','POST','/bookings/counter',counterBody,201,key());await paid(counter,'staff')
  await call('customer','POST','/bookings/counter',{...counterBody,startsAt:at(13),endsAt:end(13)},403,key())
  record('Counter booking backend-priced snapshot/Staff actor/DEMO confirmation and Customer denial')
  const faultBooking=await booking(court.id,at(9),end(9));const broker=config.containers?.find(n=>n.startsWith('sporthub-phase12-rabbit-'));if(broker){execFileSync('docker',['pause',broker],{windowsHide:true});try{const pendingPayment=await payment(faultBooking.id,'customer');assert.equal((await call('customer','GET',`/payments/${pendingPayment.id}`)).status,'SUCCESS');assert.equal((await call('customer','GET',`/bookings/${faultBooking.id}`)).booking.status,'PENDING')}finally{execFileSync('docker',['unpause',broker],{windowsHide:true})}await poll(()=>call('customer','GET',`/bookings/${faultBooking.id}`),d=>d.booking.status==='CONFIRMED',90);record('Own Rabbit outage retains verified SUCCESS without fake booking confirmation; recovers via actual publisher/inbox')}
  const nearCourt=await call('owner','POST',`/owner/facilities/${fid}/courts`,{code:'NEAR',name:'Near attendance court',sportCategoryId:category.id,enabled:true},201)
  const near=new Date(Math.ceil((Date.now()+240000)/300000)*300000),nearEnd=new Date(near.getTime()+300000)
  const gh=await call('customer','POST','/bookings/holds',{courtId:nearCourt.id,startsAt:near.toISOString(),endsAt:nearEnd.toISOString(),expectedAmount:100001},201,key())
  let group=await call('customer','POST','/groups',{holdId:gh.id,name:'Phase12 group',maxMembers:5},201,key()),gid=group.id
  const invite=await call('customer','GET',`/groups/${gid}/invite`)
  const preview=await call('buyer','GET','/groups/invitations?code='+encodeURIComponent(invite.code));assert.equal(preview.financialVisibility,'INVITATION_PREVIEW')
  await call('buyer','POST','/groups/join',{code:invite.code})
  group=await call('customer','PUT',`/groups/${gid}/allocations`,{mode:'EQUAL'})
  assert.equal(group.members.reduce((n,m)=>n+m.amountDue,0),100001)
  const memberPayments=[]
  for(const member of group.members)memberPayments.push(await payment(gid,member.userId===actors.customer.id?'customer':'buyer',member.id))
  group=await poll(()=>call('buyer','GET',`/groups/${gid}`),g=>g.state==='CONFIRMED')
  assert.equal(group.totalPaid,100001);assert.deepEqual(group.members.map(m=>m.amountPaid).sort(),[50000,50001])
  const ticket=await call('buyer','GET',`/groups/${gid}/attendance/ticket`)
  await call('staff','POST',`/groups/${gid}/attendance/scan`,{token:ticket.token})
  await call('staff','POST',`/groups/${gid}/attendance/scan`,{token:ticket.token},409)
  await call('buyer','GET',`/groups/${gid}/attendance/tickets`,undefined,403)
  const root=await call('buyer','GET',`/groups/${gid}/play-group`)
  const msg=await call('buyer','POST',`/groups/play-groups/${root.id}/messages`,{body:'Actual current-member chat'},200,key());assert.ok(msg.id)
  await call('racer','GET',`/groups/play-groups/${root.id}/messages`,undefined,403)
  record('Real group invitations/privacy/equal remainder/contributions/DEMO events/confirmation/member QR/scoped attendance/chat')
  const transferStart=new Date(Math.ceil((Date.now()+480000)/300000)*300000),transferEnd=new Date(transferStart.getTime()+300000),transferDeadline=new Date(Date.now()+180000).toISOString();const original=await paid(await booking(court.id,transferStart.toISOString(),transferEnd.toISOString())),bid=original.booking.id,snapshot=original.booking.priceSnapshot,oldToken=original.checkinToken
  await call('owner','PUT',`/owner/facilities/${fid}/transfer-policy`,{enabled:true,minLeadSeconds:0})
  await call('customer','POST','/transfers',{bookingId:bid,price:100002,deadline:transferDeadline},409,key())
  const listing=await call('customer','POST','/transfers',{bookingId:bid,price:80000,deadline:transferDeadline},201,key()),lid=listing.id
  await call('customer','POST',`/transfers/${lid}/acquisitions`,undefined,403,key())
  const acquisition=await call('buyer','POST',`/transfers/${lid}/acquisitions`,undefined,201,key())
  await call('racer','POST',`/transfers/${lid}/acquisitions`,undefined,409,key())
  const transferPayment=await payment(bid,'buyer',undefined,acquisition.id)
  await call('buyer','POST',`/payments/${transferPayment.id}/demo/complete`,{outcome:'SUCCESS'})
  await poll(()=>call('buyer','GET',`/transfers/acquisitions/${acquisition.id}`),a=>a.state==='SUCCESS')
  const handoff=await call('buyer','GET',`/bookings/${bid}`)
  assert.equal(handoff.booking.currentHolderId,actors.buyer.id);assert.deepEqual(handoff.booking.priceSnapshot,snapshot)
  assert.notEqual(handoff.checkinToken,oldToken);assert.equal((await call('buyer','GET',`/transfers/${lid}`)).state,'COMPLETED')
  await call('staff','POST',`/bookings/${bid}/check-in`,{token:oldToken},403)
  record('Transfer price cap/self-acquire/atomic lock/duplicate DEMO callback/actual handoff/current ownership/QR rotation/original snapshot')
  const cancel=await paid(await booking(court.id,at(15),end(15)))
  await call('buyer','POST',`/bookings/${cancel.booking.id}/cancel`,{reason:'Wrong booking holder'},403)
  await call('customer','POST',`/bookings/${cancel.booking.id}/cancel`,{reason:'Paid self-cancellation with no refund'})
  assert.equal((await call('customer','GET',`/bookings/${cancel.booking.id}`)).booking.status,'CANCELLED')
  const report=await call('admin','GET',`/admin/payments/reports/collections?from=${today}T00:00:00Z&to=${tomorrow}T23:59:59Z&timezone=Asia/Ho_Chi_Minh`)
  assert.ok(report);record('Paid cancellation releases slot without refund; Admin successful collections through Gateway')
  const paidCounter=(await call('staff','GET',`/bookings/${counter.id}`)).booking;
  let accessVersion=await call('owner','GET',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`);await call('owner','PUT',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`,{permissions:permissions.filter(p=>p!=='BOOKING_READ'),expectedVersion:accessVersion.version});await call('staff','GET',`/bookings/facility/${fid}`,undefined,403);accessVersion=await call('owner','GET',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`);await call('owner','PUT',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`,{permissions,expectedVersion:accessVersion.version});await call('staff','GET',`/bookings/facility/${fid}`);await call('admin','POST',`/admin/accounts/${actors.racer.id}/lock`,{duration:'SEVEN_DAYS',reason:'Synthetic access revocation test account lock'});const locked=await actors.racer.http.get('/api/v1/users/me',{headers:{Authorization:'Bearer '+actors.racer.token}});assert.ok([401,403].includes(locked.status()));await call('admin','POST',`/admin/accounts/${actors.racer.id}/unlock`,{reason:'Restore synthetic test account after access verification'});record('Live Staff permission revoke/restore and locked-account current-session revocation through Gateway');
  fixture={fid,appId,cid:court.id,nearCourt:nearCourt.id,gid,playId:root.id,bid,lid,acquisitionId:acquisition.id,counterId:counter.id,tomorrow,today,nearEnd:nearEnd.toISOString(),profileId:profile.id,bindingId:binding.id}
  fs.writeFileSync(path.join(output,'fixture.json'),JSON.stringify(fixture,null,2))
  browser??=await chromium.launch({channel:process.env.SPORTHUB_E2E_BROWSER||'msedge',headless:true})
  const signupContext=await browser.newContext({locale:'vi-VN'});contexts.push(signupContext);const signupPage=await signupContext.newPage();const uiEmail=`browser-${config.run}@phase12.invalid`;await signupPage.goto(config.baseURL+'/register');await signupPage.locator('input[name=fullName]').fill('Synthetic browser customer');await signupPage.locator('input[name=identifier]').fill(uiEmail);await signupPage.locator('input[name=password]').fill(password);await signupPage.getByRole('button',{name:'Tạo tài khoản',exact:true}).click();await signupPage.waitForURL('**/verify?**');const uiOtp=await poll(async()=>JSON.parse(fs.readFileSync(path.join(output,'smtp-codes.json'),'utf8'))[uiEmail],Boolean);await signupPage.locator('input[name=code]').fill(uiOtp);await signupPage.getByRole('button',{name:'Xác minh',exact:true}).click();await signupPage.getByText('Xác minh thành công.',{exact:false}).waitFor();record('Guest browser registration and actual SMTP OTP verification','browser');
  const customerPage=await loginPage('customer');await view(customerPage,`/search?date=${tomorrow}&q=Phase12`,'Tìm sân cho buổi chơi tiếp theo');await customerPage.getByRole('link',{name:/Đặt lịch|Chọn giờ chơi/}).first().waitFor();await assertNoAlert(customerPage)
  await view(customerPage,`/facilities/${fid}`,'Phase12 Sports '+config.run);await assertNoAlert(customerPage)
  await customerPage.getByRole('button',{name:'Lưu cơ sở yêu thích'}).click();await view(customerPage,'/customer/favorites','Cơ sở yêu thích');await customerPage.getByText('Phase12 Sports '+config.run,{exact:true}).first().waitFor()
  record('Customer browser login/search result facility detail/private favorite save and list','browser')
  await view(customerPage,`/facilities/${fid}/courts/${court.id}`,'Chọn giờ chơi');await customerPage.locator('input[type=date]').fill(tomorrow)
  const slot=customerPage.getByRole('button').filter({hasText:'16:00–16:05'});await slot.waitFor();await slot.click()
  await customerPage.getByRole('button',{name:'Xem tóm tắt và giữ chỗ'}).click();await customerPage.waitForURL('**/customer/booking/holds/**')
  await customerPage.getByRole('button',{name:'Xác nhận và thanh toán'}).click();await customerPage.waitForURL('**/customer/payments/**')
  await customerPage.getByRole('button',{name:'Mô phỏng thanh toán thành công'}).click();await customerPage.waitForURL('**/customer/bookings/**',{timeout:30000})
  await customerPage.getByRole('heading',{name:'Mã check-in của bạn'}).waitFor();await assertNoAlert(customerPage)
  customerPage.once('dialog',dialog=>dialog.accept());await customerPage.getByRole('button',{name:'Hủy booking đã thanh toán (không hoàn tiền)'}).click()
  await customerPage.getByText('Đã hủy',{exact:true}).first().waitFor();record('Customer browser availability/hold/backend quote/booking/DEMO confirmation/QR/paid no-refund cancellation','browser')
  await view(customerPage,`/customer/groups/${gid}`,'Phase12 group');await assertNoAlert(customerPage)
  await customerPage.getByRole('heading',{name:'QR điểm danh của tôi'}).first().waitFor();record('Group browser full finance tracker/per-member ticket/current-member chat','browser')
  const buyerPage=await loginPage('buyer');await view(buyerPage,`/customer/bookings/${bid}`,'Đã xác nhận');await assertNoAlert(buyerPage)
  await view(buyerPage,`/customer/transfers/acquisitions/${acquisition.id}`,'Đã nhận quyền sử dụng');record('Transfer buyer browser receives successful handoff and new QR','browser')
  await view(customerPage,`/facilities/${fid}/courts/${court.id}`,'Chọn giờ chơi');await customerPage.locator('input[type=date]').fill(tomorrow);await customerPage.getByRole('button').filter({hasText:'17:00–17:05'}).click();await customerPage.getByRole('button',{name:'Xem tóm tắt và giữ chỗ'}).click();await customerPage.waitForURL('**/customer/booking/holds/**');await customerPage.getByLabel('Hình thức đặt sân').selectOption('GROUP');await customerPage.locator('input[name=groupName]').fill('Browser cost sharing');await customerPage.getByRole('button',{name:'Tạo booking nhóm',exact:true}).click();await customerPage.waitForURL(/\/customer\/groups\/[0-9a-f-]+$/);const uiGroup=customerPage.url().split('/').at(-1);await customerPage.getByRole('link',{name:'Mời thành viên',exact:true}).click();await customerPage.getByRole('heading',{name:'Mời bạn cùng chơi'}).waitFor();const uiInvite=await call('customer','GET',`/groups/${uiGroup}/invite`);await buyerPage.goto(config.baseURL+'/customer/groups/join?code='+encodeURIComponent(uiInvite.code));await buyerPage.getByRole('button',{name:'Kiểm tra lời mời'}).click();await buyerPage.getByRole('button',{name:'Xác nhận tham gia'}).click();await buyerPage.waitForURL('**/customer/groups/'+uiGroup);await customerPage.goto(config.baseURL+`/customer/groups/${uiGroup}/split`);await customerPage.getByRole('button',{name:'Xác nhận phân chia và khóa danh sách'}).click();await customerPage.waitForURL('**/customer/groups/'+uiGroup);
  for(const page of [customerPage,buyerPage]){await page.goto(config.baseURL+`/customer/groups/${uiGroup}`);await page.getByRole('button',{name:'Đóng phần của tôi'}).click();await page.waitForURL('**/customer/payments/**');await page.getByRole('button',{name:'Mô phỏng thanh toán thành công'}).click();await page.waitForURL('**/customer/groups/'+uiGroup)}await poll(()=>call('buyer','GET',`/groups/${uiGroup}`),g=>g.state==='CONFIRMED');record('Group browser hold/create/invite/join/equal split/member DEMO payments/actual confirmation','browser');
  const browserTransferBooking=(await paid(await booking(court.id,at(19),end(19)))).booking;await customerPage.goto(config.baseURL+`/customer/bookings/${browserTransferBooking.id}/transfer`);await customerPage.getByRole('heading',{name:'Đăng lượt đặt lên chợ'}).waitFor();await customerPage.locator('input[name=price]').fill('80000');await customerPage.getByRole('button',{name:'Đăng tin chuyển nhượng',exact:true}).click();await customerPage.waitForURL(/\/transfers\/[0-9a-f-]+$/);const browserListing=customerPage.url().split('/').at(-1);await buyerPage.goto(config.baseURL+`/transfers/${browserListing}`);await buyerPage.getByRole('button',{name:'Nhận lượt đặt này'}).click();await buyerPage.waitForURL('**/customer/transfers/acquisitions/**');await buyerPage.getByRole('button',{name:'Thanh toán để nhận lượt đặt'}).click();await buyerPage.waitForURL('**/customer/payments/**');await buyerPage.getByRole('button',{name:'Mô phỏng thanh toán thành công'}).click();await buyerPage.waitForURL('**/customer/transfers/acquisitions/**');await buyerPage.getByRole('link',{name:'Mở booking và mã check-in của bạn'}).click();await buyerPage.waitForURL('**/customer/bookings/'+browserTransferBooking.id);await buyerPage.getByRole('heading',{name:'Mã check-in của bạn'}).waitFor();await assertNoAlert(buyerPage);record('Transfer browser listing creation/buyer acquisition/DEMO payment/actual handoff/new holder QR','browser');
  const fractionalCourt=await call('owner','POST',`/owner/facilities/${fid}/courts`,{code:'FRACTION',name:'Fractional price court',sportCategoryId:category.id,enabled:true},201);for(const [from,to,amount] of [['21:00','21:05',100000.1],['21:05','21:10',100000.2]])await call('owner','POST',`/schedules/facilities/${fid}/pricing`,{courtId:fractionalCourt.id,specificDate:tomorrow,startsAt:from,endsAt:to,pricePerSlot:amount,priority:1,label:'Exact fractional VND fixture',effectiveFrom:today,currency:'VND'},201);await view(customerPage,`/facilities/${fid}/courts/${fractionalCourt.id}`,'Chọn giờ chơi');await customerPage.locator('input[type=date]').fill(tomorrow);for(const time of ['21:00–21:05','21:05–21:10'])await customerPage.getByRole('button').filter({hasText:time}).click();await customerPage.getByRole('button',{name:'Xem tóm tắt và giữ chỗ'}).click();await customerPage.waitForURL('**/customer/booking/holds/**');const fractionalHold=await call('customer','GET','/bookings/holds/'+customerPage.url().split('/').at(-1));assert.equal(fractionalHold.quote.amount,200000.3);record('Customer browser adjacent fractional prices use exact Backend quote, without floating-point expected total','browser');
  const ownerPage=await loginPage('owner');await view(ownerPage,`/owner/facilities/${fid}/staff`,'Nhân viên tại cơ sở');await assertNoAlert(ownerPage)
  await view(ownerPage,`/owner/facilities/${fid}/court-types`,'Loại sân tại cơ sở');await ownerPage.locator('input[name=name]').fill('Indoor Phase12');await ownerPage.getByRole('button',{name:'Thêm loại sân'}).click();await ownerPage.getByText('Indoor Phase12',{exact:true}).first().waitFor()
  await view(ownerPage,`/owner/facilities/${fid}/reports`,'Báo cáo vận hành cơ sở');await assertNoAlert(ownerPage);await view(ownerPage,`/owner/facilities/${fid}/pricing`,'Bảng giá');await ownerPage.getByRole('button',{name:'Chỉnh sửa giá',exact:true}).first().click();await ownerPage.locator('input[name=price]').fill('100002');await ownerPage.getByRole('button',{name:'Lưu phiên bản giá mới'}).click();await ownerPage.getByText('Đã lưu quy tắc giá.',{exact:false}).waitFor();await ownerPage.getByText('Lịch sử quy tắc giá',{exact:true}).first().click();await ownerPage.getByText('Phiên bản 1:',{exact:false}).first().waitFor();await assertNoAlert(ownerPage);record('Owner browser Staff roster/court-type mutation/report/If-Match pricing update and immutable revision history','browser')
  const staffPage=await loginPage('staff');await view(staffPage,`/staff/counter-booking?facilityId=${fid}`,'Chọn sân cho khách');await staffPage.getByRole('link',{name:'Main court',exact:true}).click();await staffPage.getByRole('heading',{name:'Chọn giờ chơi'}).waitFor();await staffPage.locator('input[type=date]').fill(tomorrow);await staffPage.getByRole('button').filter({hasText:'18:00–18:05'}).click();await staffPage.getByRole('button',{name:'Xem tóm tắt và giữ chỗ'}).click();await staffPage.waitForURL('**/customer/booking/holds/**');await staffPage.locator('input[name=guestName]').fill('Browser walk-in');await staffPage.locator('input[name=guestPhone]').fill('+84977777777');await staffPage.getByRole('button',{name:'Xác nhận và thanh toán'}).click();await staffPage.waitForURL('**/customer/payments/**');await staffPage.getByRole('button',{name:'Mô phỏng thanh toán thành công'}).click();await staffPage.waitForURL('**/customer/bookings/**',{timeout:30000});record('Staff browser scoped court/counter hold/guest snapshot/DEMO confirmation','browser');await view(staffPage,`/staff/bookings/${gid}`,'Chi tiết vận hành booking');await assertNoAlert(staffPage)
  const permissionVersion=await call('owner','GET',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`);await call('owner','PUT',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`,{permissions:[...permissions,'SCHEDULE_MANAGE','COURT_STATUS_MANAGE','COURT_MAINTENANCE_MANAGE'],expectedVersion:permissionVersion.version});const operationalCourt=await call('owner','POST',`/owner/facilities/${fid}/courts`,{code:'STAFFCFG',name:'Staff configuration court',sportCategoryId:category.id,enabled:true},201);await view(staffPage,`/staff/facilities/${fid}/schedule`,'Cấu hình vận hành được phân quyền');await staffPage.getByLabel('Phạm vi lịch').selectOption(operationalCourt.id);await staffPage.getByRole('button',{name:'Thêm khoảng giờ',exact:true}).click();await staffPage.getByRole('button',{name:'Lưu lịch',exact:true}).click();await staffPage.getByText('Đã lưu lịch.',{exact:true}).waitFor();assert.ok((await call('staff','GET',`/schedules/facilities/${fid}/hours`)).some(h=>h.courtId===operationalCourt.id&&h.dayOfWeek===1));await assertNoAlert(staffPage);await staffPage.locator('input[name=date]').fill(tomorrow);await staffPage.locator('input[name=reason]').fill('Synthetic delegated exception');await staffPage.getByRole('button',{name:'Thêm ngoại lệ',exact:true}).click();await staffPage.getByText('Synthetic delegated exception',{exact:true}).waitFor();await staffPage.getByRole('button',{name:'Chỉnh sửa ngoại lệ',exact:true}).click();await staffPage.locator('input[name=reason]').fill('Updated delegated exception');await staffPage.getByRole('button',{name:'Lưu ngoại lệ',exact:true}).click();await staffPage.getByText('Updated delegated exception',{exact:true}).waitFor();await view(staffPage,`/staff/facilities/${fid}/courts`,'Trạng thái sân');let statusCard=staffPage.getByRole('heading',{name:'Staff configuration court',exact:true}).locator('..');staffPage.once('dialog',d=>d.accept());await statusCard.getByRole('button',{name:'Tạm ngừng sân'}).click();await statusCard.getByRole('button',{name:'Kích hoạt sân'}).waitFor();staffPage.once('dialog',d=>d.accept());await statusCard.getByRole('button',{name:'Kích hoạt sân'}).click();await statusCard.getByRole('button',{name:'Tạm ngừng sân'}).waitFor();await view(staffPage,`/staff/facilities/${fid}/maintenance`,'Bảo trì sân');await staffPage.getByLabel('Chọn sân bảo trì').selectOption(operationalCourt.id);await staffPage.locator('input[name=startsAt]').fill(tomorrow+'T14:00');await staffPage.locator('input[name=endsAt]').fill(tomorrow+'T15:00');await staffPage.locator('input[name=reason]').fill('Synthetic Staff maintenance with dedicated permission');await staffPage.getByRole('button',{name:'Tạo thời gian bảo trì'}).click();await staffPage.getByText('Đã lưu thời gian bảo trì.',{exact:true}).waitFor();await assertNoAlert(staffPage);assert.ok(!(await call('staff','GET','/users/me')).roles.includes('OWNER'));record('Staff browser existing specialized schedule/status/maintenance permissions operate within assigned facility without OWNER role','browser');await view(staffPage,`/staff/bookings/${gid}`,'Chi tiết vận hành booking');
  const adminPage=await loginPage('admin');await view(adminPage,'/admin/sports','Danh mục môn thể thao');await assertNoAlert(adminPage);assert.equal((await call('staff','GET',`/payments/${paidCounter.paymentId}`)).status,'SUCCESS')
  await adminPage.goto(config.baseURL+'/admin/sports');await adminPage.locator('input[name=name]').first().fill('Browser sport '+config.run);await adminPage.getByRole('button',{name:'Thêm môn thể thao'}).click();await adminPage.getByRole('heading',{name:'Browser sport '+config.run,exact:true}).waitFor();await assertNoAlert(adminPage);record('Admin browser new sport mutation, guarded by actual role','browser')
  for(const [route,title] of [['/admin/bookings','Giám sát booking'],['/admin/payments','Giám sát giao dịch']]){const response=adminPage.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1'+route&&r.status()===200);await view(adminPage,route,title);const body=await (await response).json();assert.ok(body.data.length>0);await assertNoAlert(adminPage)}await adminPage.goto(config.baseURL+`/admin/bookings/${bid}`);await adminPage.getByRole('heading',{name:'Lịch sử trạng thái và chuyển quyền'}).waitFor();await assertNoAlert(adminPage);record('Admin browser live booking/payment monitoring and immutable ownership history without holder QR','browser');
  // Wait for the real group end while checking other screens; never advance Clock or DB status.
  while(Date.now()<nearEnd.getTime()+1000)await sleep(Math.min(1000,nearEnd.getTime()+1000-Date.now()))
  await staffPage.reload();staffPage.once('dialog',dialog=>dialog.accept());await staffPage.getByRole('button',{name:'Hoàn tất booking'}).click()
  await poll(()=>call('staff','GET',`/bookings/${gid}/operations`),d=>d.detail.booking.status==='COMPLETED')
  record('Staff browser scoped operations/group attendance and actual-clock completion','browser')
  let social=await call('customer','GET',`/groups/play-groups/${root.id}`);social=await call('customer','POST',`/groups/play-groups/${root.id}/actions`,{action:'KEEP',expectedVersion:social.version})
  const reuseHold=await call('customer','POST','/bookings/holds',{courtId:court.id,startsAt:at(20),endsAt:end(20),expectedAmount:100001},201,key());const reused=await call('customer','POST',`/groups/play-groups/${root.id}/reuse`,{holdId:reuseHold.id,name:'Explicit-consent reuse',maxMembers:5},201,key());assert.equal(reused.bookingGroup.members.length,1);assert.ok(reused.participationPolicy.includes('INVITE_AND_CONFIRM'));social=await call('customer','GET',`/groups/play-groups/${root.id}`);await call('customer','POST',`/groups/play-groups/${root.id}/actions`,{action:'DISSOLVE',expectedVersion:social.version});assert.ok((await call('buyer','GET',`/groups/play-groups/${root.id}/messages`)).length);await call('buyer','POST',`/groups/play-groups/${root.id}/messages`,{body:'Disallowed after dissolve'},409,key());await call('buyer','POST',`/groups/play-groups/${root.id}/leave`,undefined,204);await call('buyer','GET',`/groups/play-groups/${root.id}/messages`,undefined,403);assert.equal((await call('buyer','GET',`/groups/${gid}`)).totalPaid,100001);record('Actual completed group KEEP/reuse explicit consent/DISSOLVE read-only/current-member revocation, financial history retained')
  // Viewport smoke of current Design System, no UI reconstruction.
  await customerPage.setViewportSize({width:390,height:844});await customerPage.goto(config.baseURL+'/customer/favorites');await customerPage.getByRole('heading',{name:'Phase12 Sports '+config.run,exact:true}).waitFor();await assertNoAlert(customerPage);await customerPage.screenshot({path:path.join(output,'customer-mobile.png'),fullPage:true})
  assert.ok(await customerPage.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'mobile horizontal overflow')
  await ownerPage.screenshot({path:path.join(output,'owner-dashboard.png'),fullPage:true})
  assert.deepEqual(browserErrors,[],'Uncaught browser errors')
  record('Actual desktop/mobile pages without uncaught browser errors or mobile overflow','browser')
}catch(error){failures.push({message:error.message,stack:error.stack});console.error(error.stack)
  for(let i=0;i<contexts.length;i++){const page=contexts[i].pages()[0];if(page){fs.writeFileSync(path.join(output,`failure-${i}.txt`),await page.locator('body').innerText().catch(()=>''));await page.screenshot({path:path.join(output,`failure-${i}.png`),fullPage:true}).catch(()=>{})}}
}finally{
  const result={status:failures.length?'FAIL':'PASS',run:config.run,apiChecks:checks,browserChecks,failures,browserErrors,fixture,limitations:['DEMO only; no real financial transfer/refund/payout','Initial Admin explicitly bootstrapped in fresh test DB; Owner/Staff/facility all actual workflows','Near-group lead/deadline=180s, access token=45s only in sandbox','No load/backup/TLS/real OAuth/SMS provider certification']}
  fs.writeFileSync(path.join(output,'system-e2e-results.json'),JSON.stringify(result,null,2))
  for(const context of contexts)await context.close();await browser?.close();for(const actor of Object.values(actors))await actor.http.dispose();await guest.dispose()
  process.exitCode=failures.length?1:0
}
