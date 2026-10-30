import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const load = name => JSON.parse(readFileSync(new URL(`../../../contracts/openapi/v1/${name}.yaml`, import.meta.url), 'utf8'))
const transfer = load('transfer')

test('peer commands cannot choose payer, holder, recipient or a bank-verified outcome', () => {
  for (const name of ['PeerCommand']) {
    const schema = transfer.components.schemas[name]
    assert.equal(schema.additionalProperties, false)
    for (const forbidden of ['holderId', 'buyerId', 'sellerId', 'bankAccount', 'confirmationMethod']) {
      assert.equal(forbidden in schema.properties, false)
    }
  }
  assert.equal(transfer.paths['/transfers/acquisitions/{id}/declare-payment'].post.requestBody.content['application/json'].schema.$ref, '#/components/schemas/PeerCommand')
  assert.equal(transfer.paths['/transfers/acquisitions/{id}/open-dispute'], undefined)
  assert.equal(transfer.components.schemas.PeerAttempt.properties.confirmationMethod.enum.includes('BANK_VERIFIED'), false)
})

test('peer party commands are private and safely replayed', () => {
  for (const [path, item] of Object.entries(transfer.paths)) {
    if (!/declare-payment|confirm-receipt|deny-receipt|conversations|\/release$/.test(path)) continue
    for (const [method, operation] of Object.entries(item)) {
      assert.deepEqual(operation.security, [{ bearerAuth: [] }], path)
      if (method === 'post') assert.ok(operation.parameters.some(p => p.name === 'Idempotency-Key' && p.required), path)
    }
  }
})

test('peer public terms expose consent without recipient identity and private terms cannot carry a facility fee', () => {
  const schemas = transfer.components.schemas
  for (const forbidden of ['sellerId','buyerId','bookingId','bankDestinationRevisionId','destinationHash','accountNumber','feeBps','quotedFeeAmount','quotedSellerNetAmount']) assert.equal(forbidden in schemas.PeerPublicTerms.properties,false,forbidden)
  for (const name of ['PeerPublicTerms','PeerSellerTerms','PeerAcquisitionTerms']) {
    assert.equal(schemas[name].additionalProperties,false)
    assert.ok(schemas[name].required.includes('bankSharingConsent'))
    assert.deepEqual(schemas[name].properties.bankSharingConsent.enum,['BUYER_AND_SEPAY_QR_V1'])
    assert.equal('feeBps' in schemas[name].properties,false)
    assert.equal('accountNumber' in schemas[name].properties,false)
  }
  assert.ok(schemas.Listing.properties.financeTerms.anyOf.some(value=>value.$ref.endsWith('/PeerPublicTerms')))
  assert.ok(schemas.Acquisition.properties.financeTerms.anyOf.some(value=>value.$ref.endsWith('/PeerAcquisitionTerms')))
  assert.equal(schemas.EditListing.properties.expectedVersion.minimum,0)
})

test('bank permission and quote revisions are explicit without exposing the account number', () => {
  const payment = load('payment')
  assert.equal(payment.components.schemas.BankProfile.properties.canRegisterFirst.type, 'boolean')
  assert.equal(payment.components.schemas.BankProfile.properties.canChange.type, 'boolean')
  assert.equal('accountNumber' in payment.components.schemas.BankProfile.properties, false)
  assert.equal(payment.components.schemas.BankDestinationInput.properties.accountNumber.writeOnly, true)
  assert.ok(load('booking').components.schemas.HoldInput.properties.quoteHash.pattern)
  const schedule = load('schedule')
  assert.equal(schedule.components.schemas.Quote.properties.durationMinutes.maximum, 240)
  assert.deepEqual(schedule.components.schemas.FlexibleBookingPolicy.properties.selectionStepMinutes.enum, [30])
})

test('peer instructions use a private image path and disclose no raw bank URL or assurance', () => {
  const payment = load('payment')
  const fields = payment.components.schemas.PeerQrInstruction.properties
  for (const forbidden of ['accountNumber', 'checkoutUrl', 'qrCode', 'apiKey', 'checksumKey', 'feeAmount']) {
    assert.equal(forbidden in fields, false)
  }
  assert.deepEqual(fields.confirmationMethod.enum, ['NONE'])
  assert.deepEqual(fields.accountVerification.enum, ['SELF_DECLARED'])
  for (const suffix of ['instruction', 'qr']) {
    const operation = payment.paths[`/payments/peer-acquisitions/{id}/${suffix}`].get
    assert.deepEqual(operation.security, [{ bearerAuth: [] }])
    assert.equal(operation.parameters.length, 1)
    assert.equal(operation.parameters[0].name, 'id')
  }
})

test('all new local schema references and exact commands resolve', () => {
  for (const name of ['booking', 'schedule', 'transfer', 'payment']) {
    const doc = load(name)
    function visit(node) {
      if (!node || typeof node !== 'object') return
      if (node.$ref?.startsWith('#/')) assert.ok(node.$ref.slice(2).split('/').reduce((value, part) => value?.[part], doc), node.$ref)
      if (node.additionalProperties === false) for (const key of node.required ?? []) assert.ok(node.properties?.[key], key)
      Object.values(node).forEach(visit)
    }
    visit(doc)
  }
})
