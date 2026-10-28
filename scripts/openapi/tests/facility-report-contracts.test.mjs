import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const root = new URL('../../../', import.meta.url)
const contract = name => JSON.parse(readFileSync(new URL(`contracts/openapi/v1/${name}.yaml`, root), 'utf8'))
const booking = contract('booking')
const admin = contract('admin')

test('additive reports keep Owner security and exact-money response schemas', () => {
  for (const path of ['dashboard', 'transactions']) {
    const get = booking.paths[`/bookings/reports/facility/{id}/${path}`].get
    assert.deepEqual(get.security, [{ bearerAuth: [] }])
    assert.deepEqual(get.parameters.filter(p => /Date$/.test(p.name)).map(p => p.name), ['fromDate', 'toDate'])
    assert.equal(get.parameters.some(p => p.name === 'timezone'), false)
    assert.ok(get.responses['400'])
  }
  const schemas = booking.components.schemas
  assert.deepEqual(schemas.FacilityReportMoneyClass.enum, ['REAL', 'DEMO', 'UNKNOWN'])
  const totals = schemas.FacilityReportMoneyTotals.properties
  for (const property of ['gross', 'policyRefundExecuted', 'afterRefund', 'refundDue', 'refundInFlight', 'refundFundingPending']) {
    assert.equal(totals[property].type, 'string')
    assert.equal(totals[property].nullable, true)
    assert.match('99999999999999999999.25', new RegExp(totals[property].pattern))
  }
  assert.match('-80000', new RegExp(totals.afterRefund.pattern))
  assert.deepEqual(schemas.FacilityReportTransaction.properties.kind.enum, ['COURT_COLLECTION', 'FACILITY_POLICY_REFUND'])
})

test('all new report schema references resolve and required properties exist', () => {
  const inspect = value => {
    if (!value || typeof value !== 'object') return
    if (value.$ref?.startsWith('#/')) {
      const target = value.$ref.slice(2).split('/').reduce((node, part) => node?.[part], booking)
      assert.ok(target, value.$ref)
    }
    for (const key of value.required ?? []) assert.ok(value.properties?.[key], key)
    for (const child of Object.values(value)) inspect(child)
  }
  for (const [name, schema] of Object.entries(booking.components.schemas)) {
    if (name.startsWith('FacilityReport')) inspect(schema)
  }
})

test('legacy Owner report and personal booking contract stay available', () => {
  const legacy = admin.paths['/bookings/reports/facility/{id}'].get
  assert.equal(legacy.operationId, 'ownerFacilityReport')
  assert.deepEqual(legacy.security, [{ bearerAuth: [] }])
  assert.deepEqual(legacy.parameters.filter(p => p.in === 'query').map(p => p.name), ['from', 'to'])
  assert.ok(booking.paths['/bookings/holds'].post)
  assert.ok(booking.components.schemas.Booking.properties.currentHolderId)
})

test('facility policy and Staff refund contracts keep configuration and execution rights distinct', () => {
  const facility = contract('facility')
  const identity = contract('identity')
  const input = facility.components.schemas.FacilityRefundPolicyInput
  assert.deepEqual(input.required, ['rateBps', 'version'])
  assert.equal(input.properties.rateBps.minimum, 0)
  assert.equal(input.properties.rateBps.maximum, 10000)
  assert.equal(input.properties.rateBps.type, 'integer')
  assert.equal(Object.hasOwn(input.properties.rateBps, 'default'), false)
  assert.deepEqual(facility.paths['/owner/facilities/{id}/refund-policy'].put.security, [{ bearerAuth: [] }])
  const permissions = identity.components.schemas.AssignedPermission.enum
  assert.ok(permissions.includes('BOOKING_REFUND'))
  assert.equal(permissions.includes('BOOKING_COMPLETE'), false)
  assert.equal(permissions.includes('BOOKING_CANCEL_PENDING'), false)
  assert.deepEqual(identity.components.schemas.StaffPermissionsInput.required, ['permissions', 'expectedVersion'])
  assert.ok(identity.paths['/staff-bindings/facilities/{facilityId}/permission-catalog'].get)
})

test('DEMO execution accepts only an outcome and never client amounts or recipients', () => {
  const schemas = booking.components.schemas
  assert.deepEqual(schemas.DemoMoneyOutcome.required, ['outcome'])
  assert.deepEqual(Object.keys(schemas.DemoMoneyOutcome.properties), ['outcome'])
  for (const [path, id] of [['settlements/{id}/pay-seller', 'demoPaySeller'], ['refunds/{id}/complete', 'demoCompleteRefund']]) {
    const operation = booking.paths[`/bookings/demo-finance/${path}`].post
    assert.equal(operation.operationId, id)
    assert.deepEqual(operation.security, [{ bearerAuth: [] }])
    assert.ok(operation.parameters.some(p => p.name === 'Idempotency-Key' && p.required))
    assert.match(operation.description, /Owner.*Staff/)
  }
  assert.equal(schemas.DemoSettlement.properties.sellerNetAmount.type, 'string')
  assert.equal(schemas.DemoRefundAllocation.properties.refundAmount.type, 'string')
  for (const route of ['cancellation-quote', 'cancel-with-policy']) {
    assert.equal(booking.paths[`/bookings/{id}/${route}`], undefined)
  }
})

test('check-in credentials are private and counter guest attendance preserves the guards', () => {
  assert.match(booking.components.schemas.Booking.properties.bookingCode.description, /only.*current Customer holder/)
  const operation = booking.paths['/bookings/{id}/check-in-guest'].post
  assert.deepEqual(operation.security, [{ bearerAuth: [] }])
  assert.equal(operation.requestBody, undefined)
  assert.match(operation.description, /BOOKING_CHECK_IN.*OFFLINE_COUNTER.*fully verified.*time window.*no frozen transfer/)
  assert.match(operation.description, /Rejects online or linked-Customer/)
  assert.ok(operation.responses['403'])
  assert.ok(operation.responses['409'])
})

test('DEMO report uses simple pagination, exact amounts and distinct fee/cashflow facts', () => {
  const get = booking.paths['/bookings/reports/facility/{id}/dashboard'].get
  assert.equal(get.parameters.some(p => ['cursor','limit'].includes(p.name)), false)
  assert.equal(get.parameters.find(p => p.name === 'page').schema.default, 0)
  assert.deepEqual(get.parameters.find(p => p.name === 'size').schema, { type: 'integer', minimum: 1, maximum: 100, default: 25 })
  for (const property of Object.values(booking.components.schemas.DemoReportMoney.properties)) assert.equal(property.type, 'string')
  const kinds = booking.components.schemas.DemoReportTransaction.properties.kind.enum
  assert.ok(kinds.includes('FACILITY_TRANSFER_FEE') && kinds.includes('TRANSFER_COLLECTION') && kinds.includes('TECHNICAL_REFUND'))
  assert.deepEqual(booking.components.schemas.DemoFacilityDashboard.properties.moneyAvailability.enum, ['COMPLETE', 'PARTIAL', 'UNAVAILABLE'])
})

test('DEMO transfer binds Owner-selected fee to receiving facility and explicit consent without banking inputs', () => {
  const transfer = contract('transfer'), facility = contract('facility'), identity = contract('identity')
  const terms = transfer.components.schemas.DemoTransferTerms.properties
  assert.equal(terms.feeBps.minimum, 0); assert.equal(terms.feeBps.maximum, 10000)
  assert.equal(terms.receiverFacilityId.readOnly, true)
  assert.equal(terms.receiverOwnerId.readOnly, true)
  assert.equal(Object.hasOwn(terms.feeBps, 'default'), false)
  assert.ok(terms.refundPolicy)
  assert.equal(transfer.components.schemas.CreateListing.required.includes('beneficiaryRevisionId'), false)
  assert.ok(transfer.paths['/transfers/quote'].post)
  assert.ok(transfer.paths['/transfers/{id}/quote'].post)
  assert.deepEqual(transfer.components.schemas.AcquireListing.required, ['acceptedTermsHash'])
  assert.ok(facility.paths['/owner/facilities/{id}/transfer-fee-policy'].put)
  assert.ok(identity.components.schemas.AssignedPermission.enum.includes('TRANSFER_PAYOUT'))
  const monitor = admin.paths['/admin/bookings/demo-finance']
  assert.ok(monitor.get); assert.equal(Object.hasOwn(monitor, 'post'), false)
})

test('direct peer consideration has a separate bounded page and cannot claim bank verification', () => {
  const schemas=booking.components.schemas, report=schemas.PeerFacilityReport
  assert.equal(booking.paths['/bookings/reports/facility/{id}/dashboard'].get.parameters.find(p=>p.name==='peerPage').schema.minimum,0)
  assert.equal(schemas.DemoFacilityDashboard.properties.peer.$ref,'#/components/schemas/PeerFacilityReport')
  assert.match(report.description,/never facility revenue/)
  assert.match(report.description,/pre-period cases/)
  assert.equal(schemas.PeerReportPage.properties.items.maxItems,100)
  assert.deepEqual(schemas.PeerReportRow.properties.confirmationMethod.enum,['NONE','BUYER_DECLARED','COUNTERPARTY_ACKNOWLEDGED','OWNER_ADJUDICATED'])
  for(const name of ['buyerDeclaredAmount','sellerAcknowledgedAmount','ownerAwardedAmount','returnDeclaredAmount','returnAcknowledgedAmount'])assert.equal(report.properties[name].type,'string')
  for(const name of ['bankAccountNumber','apiKey','checksumKey','bankReference','evidenceIds'])assert.equal(Object.hasOwn(schemas.PeerReportRow.properties,name),false)
})
