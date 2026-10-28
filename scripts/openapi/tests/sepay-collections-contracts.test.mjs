import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const api=JSON.parse(readFileSync(new URL('../../../contracts/openapi/v1/payment.yaml',import.meta.url)))
test('SePay facility configuration has a write-only webhook secret and masked read projection',()=>{
  const input=api.components.schemas.SepayChannelInput,read=api.components.schemas.SepayChannel
  assert.equal(input.properties.webhookApiKey.writeOnly,true)
  assert.equal(input.properties.webhookApiKey.minLength,32)
  assert.ok(input.required.includes('accountNumber'))
  assert.ok(input.required.includes('bankBin'))
  assert.equal('webhookApiKey' in read.properties,false)
  assert.equal('accountNumber' in read.properties,false)
  assert.ok(read.required.includes('maskedAccountNumber'))
  assert.deepEqual(api.paths['/payments/facilities/{id}/sepay-channel'].put.security,[{bearerAuth:[]}])
  const empty=api.paths['/payments/facilities/{id}/sepay-channel'].get.responses['204']
  assert.ok(empty,'First-time Owner setup must have an explicit empty state')
  assert.equal(empty.content,undefined)
})
test('SePay public webhook requires authentication and acknowledges only committed observations',()=>{
  const path=api.paths['/payments/providers/sepay/{channelId}/webhook'],post=path.post
  assert.equal(path.get,undefined)
  assert.deepEqual(post.security,[])
  assert.ok(post.parameters.some(p=>p.name==='Authorization'&&p.required))
  assert.deepEqual(post.responses['200'].content['application/json'].schema.properties.success.enum,[true])
})
