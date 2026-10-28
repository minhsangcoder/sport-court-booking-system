// Genuine BFF -> Gateway -> six services -> separate PG/Rabbit/MinIO.
// No page.route(), fake payment responses, owner-role or facility-state SQL.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {createRequire} from 'node:module'
import {execFileSync} from 'node:child_process'

const config=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))
assert.match(new URL(config.baseURL).hostname,/^(127\.0\.0\.1|localhost)$/)
assert.ok(Object.values(config.databases).every(n=>/^\w+_system_phase12_[a-f0-9]+$/.test(n)))
const require=createRequire(import.meta.url)
const {chromium,request}=require(process.env.SPORTHUB_E2E_PLAYWRIGHT||'playwright')
const output=config.output, password='Phase12Synthetic123!', actors={}, checks=[], browserChecks=[], failures=[]
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))
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
async function loginPage(name){const context=await browser.newContext({locale:'vi-VN',timezoneId:'Asia/Ho_Chi_Minh',viewport:{width:1440,height:1000}}),page=await context.newPage();contexts.push(context)
  page.on('pageerror',error=>browserErrors.push({url:page.url(),message:error.message}))
  await page.goto(config.baseURL+'/login');await page.locator('input[name=identifier]').fill(actors[name].email);await page.locator('input[name=password]').fill(password)
  await page.getByRole('button',{name:'Đăng nhập ngay',exact:true}).click();await page.waitForURL(url=>!url.pathname.startsWith('/login'),{timeout:25000});return page
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
  await call('owner','POST',`/owner/facilities/${fid}/courts`,{code:'MAIN',name:'Main court',sportCategoryId:category.id,enabled:true},201)
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
  async function verifyPhone(actor,phone){
    await call(actor,'PATCH','/users/me',{phone})
    const challenge=(await call(actor,'GET','/users/me/contact-challenges')).find(c=>c.channel==='PHONE')
    assert.ok(challenge,'Real phone challenge must exist')
    const recipient=phone.slice(1)+'@sms.sporthub.local'
    const otp=await poll(async()=>JSON.parse(fs.readFileSync(path.join(output,'smtp-codes.json'),'utf8'))[recipient],Boolean)
    await call(actor,'POST','/auth/verify',{challengeId:challenge.id,code:otp})
    assert.equal((await call(actor,'GET','/users/me')).phoneVerified,true)
  }
  const customerPhone='+84910000001',staffPhone='+84910000002'
  await verifyPhone('customer',customerPhone);await verifyPhone('staff',staffPhone)
  await call('owner','PUT',`/owner/facilities/${fid}/transfer-policy`,{enabled:true,minLeadSeconds:0})
  record('Verified Customer/Staff phone ownership through real OTP challenges and loopback DEMO SMS delivery')
  const courts={}
  for(const [name,code] of [['online','ONLINE'],['guest','GUEST'],['linked','LINKED'],['self','SELF']]){
    courts[name]=await call('owner','POST',`/owner/facilities/${fid}/courts`,{code,name:'Ownership '+name+' court',sportCategoryId:category.id,enabled:true},201)
  }
  const staffPage=await loginPage('staff'),customerPage=await loginPage('customer')
  async function createViaBrowser(page,name,{counter=false,guestName,guestPhone}={}){
    const startsAt=new Date(Math.ceil((Date.now()+180000)/300000)*300000)
    const endsAt=new Date(startsAt.getTime()+300000)
    const date=new Date(startsAt.getTime()+7*3600000).toISOString().slice(0,10)
    const fmt=value=>new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Ho_Chi_Minh',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(value)
    if(counter){
      await view(page,`/staff/counter-booking?facilityId=${fid}`,'Chọn sân cho khách')
      await page.getByRole('link',{name:'Ownership '+name+' court',exact:true}).click()
    }else{
      await page.goto(config.baseURL+`/facilities/${fid}/courts/${courts[name].id}`)
    }
    await page.getByRole('heading',{name:'Chọn giờ chơi'}).waitFor()
    await page.locator('input[type=date]').fill(date)
    await page.getByRole('button').filter({hasText:fmt(startsAt)+'–'+fmt(endsAt)}).click()
    await page.getByRole('button',{name:'Xem tóm tắt và giữ chỗ'}).click()
    await page.waitForURL('**/customer/booking/holds/**')
    const holdId=new URL(page.url()).pathname.split('/').at(-1)
    if(counter){await page.locator('input[name=guestName]').fill(guestName);await page.locator('input[name=guestPhone]').fill(guestPhone)}
    await page.getByRole('button',{name:'Xác nhận và thanh toán'}).click()
    await page.waitForURL('**/customer/payments/**')
    await page.getByRole('button',{name:'Mô phỏng thanh toán thành công'}).click()
    const prefix=counter&&name!=='self'?'/staff/bookings/':'/customer/bookings/'
    await page.waitForURL(url=>url.pathname.startsWith(prefix),{timeout:35000})
    const id=new URL(page.url()).pathname.split('/').at(-1)
    await assertNoAlert(page)
    return{id,holdId,startsAt:startsAt.toISOString(),endsAt:endsAt.toISOString()}
  }
  fixture.online=await createViaBrowser(customerPage,'online')
  const online=await call('customer','GET',`/bookings/${fixture.online.id}`)
  assert.equal(online.booking.createdBy,actors.customer.id);assert.equal(online.booking.currentHolderId,actors.customer.id)
  assert.ok(online.checkinToken);await customerPage.getByRole('img',{name:'QR check-in booking'}).waitFor()
  await customerPage.getByRole('link',{name:'Đăng tin chuyển nhượng lượt đặt'}).waitFor()
  record('Online Customer creates, pays through UI, returns to personal detail with owner QR and transfer control','browser')

  fixture.guest=await createViaBrowser(staffPage,'guest',{counter:true,guestName:'Browser walk-in',guestPhone:'+84977777777'})
  const guestDetail=await call('staff','GET',`/bookings/${fixture.guest.id}/operations`)
  assert.equal(guestDetail.detail.booking.createdBy,actors.staff.id)
  assert.equal(guestDetail.detail.booking.customerId,null);assert.equal(guestDetail.detail.booking.currentHolderId,null)
  assert.equal(guestDetail.detail.checkinToken,null);assert.equal(guestDetail.counterGuest.linkedCustomerId,null)
  assert.equal((await call('staff','GET',`/bookings/holds/${fixture.guest.holdId}`)).holderId,null)
  await call('staff','DELETE',`/bookings/holds/${fixture.guest.holdId}`,undefined,409)
  await call('racer','GET',`/bookings/${fixture.guest.id}`,undefined,403)
  assert.ok(!(await call('staff','GET','/bookings')).some(b=>b.id===fixture.guest.id))
  assert.equal(await staffPage.getByRole('img',{name:'QR check-in booking'}).count(),0)
  assert.equal(await staffPage.getByRole('link',{name:'Đăng tin chuyển nhượng lượt đặt'}).count(),0)
  assert.equal(await staffPage.getByTestId('operational-booking-code').count(),0)
  assert.equal(guestDetail.detail.booking.bookingCode,null)
  const guestCheckin=staffPage.waitForResponse(r=>new URL(r.url()).pathname===`/api/v1/bookings/${fixture.guest.id}/check-in-guest`&&r.status()===200)
  await staffPage.getByRole('button',{name:'Xác nhận khách vãng lai đến sân',exact:true}).click();await guestCheckin
  await staffPage.getByRole('heading',{name:'CHECKED_IN',exact:true}).waitFor();await assertNoAlert(staffPage)
  record('Guest creation, payment and scoped attendance reach CHECKED_IN without exposing a code or assigning Staff ownership','browser')

  fixture.linked=await createViaBrowser(staffPage,'linked',{counter:true,guestName:'Linked Customer at counter',guestPhone:customerPhone})
  const linked=await call('customer','GET',`/bookings/${fixture.linked.id}`)
  assert.equal(linked.booking.createdBy,actors.staff.id);assert.equal(linked.booking.currentHolderId,actors.customer.id)
  assert.ok((await call('customer','GET','/bookings')).some(b=>b.id===fixture.linked.id))
  assert.ok(!(await call('staff','GET','/bookings')).some(b=>b.id===fixture.linked.id))
  await customerPage.goto(config.baseURL+`/customer/bookings/${fixture.linked.id}`)
  await customerPage.getByRole('img',{name:'QR check-in booking'}).waitFor();await customerPage.getByRole('link',{name:'Đăng tin chuyển nhượng lượt đặt'}).waitFor()
  await assertNoAlert(customerPage)
  record('Verified linked Customer receives the counter booking personally with QR and transfer while Staff returns to operations','browser')

  const version=await call('owner','GET',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`)
  await call('owner','PUT',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`,{permissions:permissions.filter(p=>p!=='BOOKING_CHECK_IN'),expectedVersion:version.version})
  await staffPage.reload();await staffPage.getByRole('heading',{name:'Chi tiết vận hành booking'}).waitFor()
  assert.equal(await staffPage.getByRole('button',{name:'Check-in booking',exact:true}).count(),0)
  assert.equal(await staffPage.getByTestId('operational-booking-code').count(),0)
  const linkedCode=linked.booking.bookingCode
  await call('staff','POST',`/bookings/facility/${fid}/check-in-by-code`,{bookingCode:linkedCode},403)
  await call('staff','POST',`/bookings/facility/${foreign.id}/check-in-by-code`,{bookingCode:linkedCode},403)
  const freshVersion=await call('owner','GET',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`)
  await call('owner','PUT',`/staff-bindings/facilities/${fid}/${binding.id}/permissions`,{permissions,expectedVersion:freshVersion.version})
  await staffPage.reload();await staffPage.getByRole('button',{name:'Check-in booking',exact:true}).waitFor()
  await staffPage.locator('input[name=token]').first().fill('0000000000')
  await staffPage.getByRole('button',{name:'Check-in booking',exact:true}).click();await staffPage.getByRole('alert').waitFor()
  assert.equal((await call('staff','GET',`/bookings/${fixture.linked.id}`)).booking.status,'CONFIRMED')
  await staffPage.locator('input[name=token]').first().fill(linkedCode)
  await staffPage.getByRole('button',{name:'Check-in booking',exact:true}).click()
  await staffPage.getByRole('heading',{name:'CHECKED_IN',exact:true}).waitFor();await assertNoAlert(staffPage)
  record('Live permission revocation, wrong-facility denial and invalid-code errors preserve state; restored authority checks in correctly','browser')

  fixture.self=await createViaBrowser(staffPage,'self',{counter:true,guestName:'Verified Staff Customer',guestPhone:staffPhone})
  const self=await call('staff','GET',`/bookings/${fixture.self.id}`)
  assert.equal(self.booking.createdBy,actors.staff.id);assert.equal(self.booking.currentHolderId,actors.staff.id);assert.ok(self.checkinToken)
  await staffPage.getByRole('img',{name:'QR check-in booking'}).waitFor();await staffPage.getByRole('link',{name:'Đăng tin chuyển nhượng lượt đặt'}).waitFor()
  record('Self-linked Staff Customer follows the personal result route and keeps genuine Customer QR/transfer rights','browser')
  assert.deepEqual(browserErrors,[])
  await staffPage.screenshot({path:path.join(output,'ownership-staff-customer.png'),fullPage:true})
  await customerPage.screenshot({path:path.join(output,'ownership-linked-customer.png'),fullPage:true})
}catch(error){
  failures.push({message:error.message,stack:error.stack});console.error(error.stack)
  for(let i=0;i<contexts.length;i++){const page=contexts[i].pages()[0];if(page){fs.writeFileSync(path.join(output,`ownership-failure-${i}.txt`),await page.locator('body').innerText().catch(()=>''));await page.screenshot({path:path.join(output,`ownership-failure-${i}.png`),fullPage:true}).catch(()=>{})}}
}finally{
  const result={status:failures.length?'FAIL':'PASS',run:config.run,apiChecks:checks,browserChecks,failures,browserErrors,fixture,limitations:['DEMO payments only','Initial Admin fixture only; Owner/Staff/facility use real approval and binding workflows','Phone verification uses loopback test SMTP with the existing local DEMO SMS adapter','No real data correction or deployment']}
  fs.writeFileSync(path.join(output,'system-e2e-results.json'),JSON.stringify(result,null,2))
  for(const context of contexts)await context.close();await browser?.close();for(const actor of Object.values(actors))await actor.http.dispose();await guest.dispose()
  process.exitCode=failures.length?1:0
}
