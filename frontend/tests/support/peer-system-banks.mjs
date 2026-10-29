import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

export async function peerSystemBanks({config,call,actors,bank,quote,key,slot,courts,facility,poll,pass}) {
  const range={startsAt:new Date(Date.parse(slot.startsAt)+8*3600000).toISOString(),endsAt:new Date(Date.parse(slot.endsAt)+8*3600000).toISOString()}
  const phone='+84910000123'
  await call('counterCustomer','PATCH','/users/me',{phone})
  const challenge=(await call('counterCustomer','GET','/users/me/contact-challenges')).find(item=>item.channel==='PHONE')
  assert.ok(challenge)
  const code=await poll(async()=>JSON.parse(fs.readFileSync(path.join(config.output,'smtp-codes.json'),'utf8'))[phone.slice(1)+'@sms.sporthub.local'],Boolean)
  await call('counterCustomer','POST','/auth/verify',{challengeId:challenge.id,code})
  await bank('staff','000111222333')
  async function counter(court,guestPhone) {
    const priced=await quote('staff',court,range)
    return call('staff','POST','/bookings/counter',{courtId:courts[court].id,...range,expectedAmount:Number(priced.amount),quoteHash:priced.quoteHash,guestName:'Synthetic counter customer',guestPhone,paymentMethod:'BANK_QR'},201,key())
  }
  const guest=await counter('guest','+84977777123'),linked=await counter('linked',phone)
  assert.equal(guest.currentHolderId,null)
  assert.equal(linked.currentHolderId,actors.counterCustomer.id)
  assert.equal(linked.createdBy,actors.staff.id)
  assert.equal((await call('staff','GET','/payments/bank-profile')).canChange,true)
  assert.equal((await call('counterCustomer','GET','/payments/bank-profile')).canRegisterFirst,true)
  await bank('counterCustomer','000111222444')
  assert.equal((await call('counterCustomer','GET','/payments/bank-profile')).canChange,false)
  const newAccount={bankBin:'970436',accountNumber:'000999999999',accountName:'Synthetic changed',consentReference:'counter-race-'+config.run,expectedProfileVersion:1}
  assert.equal((await call('counterCustomer','POST','/payments/bank-destinations',newAccount,409,key())).code,'BANK_PROFILE_LOCKED')
  await call('b','GET',`/bookings/${guest.id}`,undefined,403)
  pass('Genuine verified phone links the counter Customer; Guest has no holder, Staff creator bank stays free, linked Customer can register first but cannot replace')

  const priced=await quote('bankHost','group',range)
  const held=await call('bankHost','POST','/bookings/holds',{courtId:courts.group.id,...range,expectedAmount:Number(priced.amount),quoteHash:priced.quoteHash},201,key())
  const group=await call('bankHost','POST','/groups',{holdId:held.id,name:'Synthetic bank responsibility group',maxMembers:3},201,key())
  const invite=await call('bankHost','GET',`/groups/${group.id}/invite`)
  await call('bankMember','POST','/groups/join',{code:invite.code})
  await bank('bankHost','000111222555');await bank('bankMember','000111222666')
  assert.equal((await call('bankMember','GET','/payments/bank-profile')).canChange,false)
  assert.equal((await call('bankHost','GET','/payments/bank-profile')).canChange,false)
  await call('bankMember','POST',`/groups/${group.id}/leave`)
  assert.equal((await call('bankMember','GET','/payments/bank-profile')).canChange,true)
  const replacement=await bank('bankMember','000111222777',1)
  assert.equal((await call('bankMember','GET','/payments/bank-profile')).activeRevisionId,replacement.id)
  await call('bankHost','POST',`/groups/${group.id}/cancel`,{reason:'Synthetic unpaid group cancelled'})
  assert.equal((await call('bankHost','GET','/payments/bank-profile')).canChange,true)
  const deadline=Math.max(Date.parse(guest.holdExpiresAt),Date.parse(linked.holdExpiresAt))
  assert.ok(Number.isFinite(deadline))
  const advanced=await fetch(config.faultControlURL+'/clock',{method:'POST',headers:{Authorization:'Bearer '+config.faultControlToken,'Content-Type':'application/json'},body:JSON.stringify({instant:new Date(deadline+1).toISOString()})})
  assert.equal(advanced.status,200,await advanced.text())
  assert.equal((await call('counterCustomer','GET','/payments/bank-profile')).canChange,true)
  assert.equal((await call('staff','GET','/payments/bank-profile')).canChange,true)
  pass('Joined group member and host lock independently; leaving/cancelling release their real sources, replacement becomes active, counter timeout unlocks Customer without touching Staff')
}
