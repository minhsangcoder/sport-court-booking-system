// Loopback-only native provider fixture. Never forwards a request to payOS or a bank.
import http from 'node:http'
import { createHmac, randomUUID } from 'node:crypto'
import assert from 'node:assert/strict'
import { mkdirSync, readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const port = Number(process.argv[2])
assert.ok(port > 0 && port <= 65535)
const root=fileURLToPath(new URL('../../',import.meta.url))
const qrTools=path.join(root,'tmp/qr-fixture-tools')
const zxing=path.join(os.homedir(),'.m2/repository/com/google/zxing/core/3.5.3/core-3.5.3.jar')
mkdirSync(qrTools,{recursive:true})
execFileSync('javac',['-cp',zxing,'-d',qrTools,path.join(root,'scripts/test/FixtureQrImage.java')],{windowsHide:true,timeout:20000})
function png(payload) {
  return execFileSync(process.env.SPORTHUB_E2E_JAVA || 'java',['-Djava.awt.headless=true','-cp',qrTools+path.delimiter+zxing,'FixtureQrImage',payload],{windowsHide:true,timeout:3000,maxBuffer:512*1024})
}
const orders = new Map(), webhooks = new Map()
const checksum = 'synthetic-payos-checksum-not-a-real-secret'
const ownerAccount=process.env.SPORTHUB_E2E_TEST_SCRIPT==='peer-resale-system-e2e.mjs'?'000555666777':'000123456789'
let fault = null, createCalls = 0
function ordered(value) {
  if (Array.isArray(value)) return value.map(ordered)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, ordered(value[key])]))
  return value
}
function sign(data) {
  const plain = Object.keys(data).sort().map(key => {
    const value = data[key]
    return `${key}=${value == null || value === 'null' || value === 'undefined' ? '' : typeof value === 'object' ? JSON.stringify(ordered(value)) : value}`
  }).join('&')
  return createHmac('sha256', checksum).update(plain).digest('hex')
}
function qr(order,account=ownerAccount) {
  const tlv = (tag, value) => tag + String(value.length).padStart(2, '0') + value
  const beneficiary = tlv('00', '970436') + tlv('01', account)
  const payload = tlv('00', '01') + tlv('01', '12') + tlv('38', tlv('00', 'A000000727') + tlv('01', beneficiary) + tlv('02', 'QRIBFTTA')) + tlv('53', '704') + tlv('54', String(order.amount)) + tlv('58', 'VN') + tlv('62', tlv('08', order.description)) + '6304'
  let crc = 0xffff
  for (const byte of Buffer.from(payload)) { crc ^= byte << 8; for (let bit = 0; bit < 8; bit++) crc = (crc & 0x8000 ? (crc << 1) ^ 0x1021 : crc << 1) & 0xffff }
  return payload + crc.toString(16).toUpperCase().padStart(4, '0')
}
function reply(response, data, status = 200) {
  response.writeHead(status, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(status === 200 ? { code: '00', desc: 'success', data, signature: sign(data) } : { code: 'NOT_FOUND', desc: 'synthetic missing order' }))
}
async function body(request) {
  let text = ''; for await (const chunk of request) { text += chunk; assert.ok(text.length < 100_000) }
  return text ? JSON.parse(text) : {}
}
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1')
    if (url.pathname === '/health') return reply(response, { fixture: true })
    if(url.pathname==='/sepay') {
      assert.equal(url.searchParams.get('bank'),'970436')
      const account=url.searchParams.get('acc'),amount=url.searchParams.get('amount'),description=url.searchParams.get('des')
      assert.match(account,/^0[0-9]{6,18}$/);assert.match(amount,/^[1-9][0-9]{0,9}$/);assert.match(description,/^(?:SC|SH)[0-9]{1,18}$/)
      const image=account==='000123456789' && amount==='80000' && description==='SH1'
        ? readFileSync(new URL('../../backend/payment-service/src/test/resources/peer-qr/sepay-vcb-synthetic.png',import.meta.url))
        : png(qr({amount:Number(amount),description},account))
      response.writeHead(200,{'Content-Type':'image/png'});return response.end(image)
    }
    if (url.pathname === '/banks') {
      response.writeHead(200, { 'Content-Type': 'application/json' })
      return response.end(JSON.stringify({ code: '00', data: [{ bin: '970436', name: 'Synthetic Vietcombank', shortName: 'VCB', transferSupported: 1 }, { bin: '970422', name: 'Synthetic MB', shortName: 'MB', transferSupported: 1 }] }))
    }
    if (url.pathname === '/__test/fault') { fault = (await body(request)).fault; return reply(response, { fault }) }
    if (url.pathname === '/__test/state') return reply(response, { createCalls, orders: [...orders.values()].map(order => ({ code: order.code, paymentLinkId: order.id, total: order.transactions.reduce((sum, item) => sum + item.amount, 0) })) })
    if (url.pathname === '/__test/credit') {
      const input = await body(request), order = orders.get(Number(input.orderCode)); assert.ok(order)
      assert.ok(Number.isSafeInteger(input.amount) && input.amount > 0)
      const reference = input.reference ?? randomUUID(), time = input.bankTime ?? new Date().toISOString()
      if (!order.transactions.some(item => item.reference === reference)) order.transactions.push({ reference, amount: input.amount, accountNumber: ownerAccount, transactionDateTime: time, currency: 'VND' })
      const data = { orderCode: order.code, amount: input.amount, accountNumber: ownerAccount, reference, paymentLinkId: order.id, currency: 'VND', code: '00', transactionDateTime: time }
      const callback = webhooks.get(order.client)
      let ack = null
      if (callback && input.webhook !== 'drop') {
        const envelope = { code: '00', success: true, data, signature: input.webhook === 'wrong' ? '0'.repeat(64) : sign(data) }
        const send = () => fetch(callback, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(envelope) })
        ack = (await send()).status
        if (input.webhook === 'duplicate') await send()
      }
      return reply(response, { reference, ack })
    }
    const client = request.headers['x-client-id']
    if (!client || request.headers['x-api-key'] !== 'synthetic-payos-api-key') return reply(response, {}, 401)
    if (url.pathname === '/confirm-webhook') {
      const input = await body(request); assert.match(input.webhookUrl, /^http:\/\/127\.0\.0\.1:\d+\/api\/v1\/payments\/providers\/payos\/[a-f0-9-]+\/webhook$/)
      webhooks.set(client, input.webhookUrl)
      return reply(response, { accountNumber: ownerAccount, accountName: 'SYNTHETIC FACILITY', webhookUrl: input.webhookUrl })
    }
    if (request.method === 'POST' && url.pathname === '/v2/payment-requests') {
      const input = await body(request); createCalls++
      assert.match(input.description, /^[A-Z0-9]{9}$/)
      const selected = Object.fromEntries(['amount', 'cancelUrl', 'description', 'orderCode', 'returnUrl'].map(key => [key, input[key]]))
      assert.equal(input.signature, sign(selected)); assert.ok(Number.isSafeInteger(input.amount) && input.amount > 0)
      let order = orders.get(input.orderCode)
      if (!order) { order = { code: input.orderCode, id: randomUUID().replaceAll('-', ''), client, amount: input.amount, description: input.description, transactions: [] }; orders.set(order.code, order) }
      if (fault === 'create-response-loss') { fault = null; request.socket.destroy(); return }
      return reply(response, { orderCode: order.code, paymentLinkId: order.id, amount: order.amount, description: order.description, currency: 'VND', status: 'PENDING', bin: '970436', accountNumber: ownerAccount, accountName: 'SYNTHETIC FACILITY', checkoutUrl: `https://pay.payos.vn/web/${order.id}`, qrCode: qr(order) })
    }
    const match = /^\/v2\/payment-requests\/(\d+)(\/cancel)?$/.exec(url.pathname)
    if (match) {
      if (fault === 'query-unavailable') return reply(response, {}, 503)
      const order = orders.get(Number(match[1])); if (!order || order.client !== client) return reply(response, {}, 404)
      const total = order.transactions.reduce((sum, item) => sum + item.amount, 0)
      return reply(response, { id: order.id, orderCode: order.code, amount: order.amount, amountPaid: total, amountRemaining: Math.max(0, order.amount - total), status: total >= order.amount ? 'PAID' : 'PENDING', transactions: order.transactions })
    }
    reply(response, {}, 404)
  } catch (error) { console.error('Synthetic provider assertion:', error.message); if (!response.headersSent) reply(response, {}, 500); else response.end() }
})
server.listen(port, '127.0.0.1', () => console.log(`Native payOS fixture listening on loopback port ${port}`))
