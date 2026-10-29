import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { fixture } from '../../scripts/performance/ui-fixture.mjs'
import { facility, id } from '../../scripts/performance/ui-data.mjs'

const root = fileURLToPath(new URL('../../', import.meta.url))
const directory = path.resolve(process.argv[2] || path.join(root, 'tmp/peer-resale-performance/candidate-hourly-ui/frontend'))
const output = path.join(root, 'tmp/peer-resale-performance/hourly-browser')
mkdirSync(output, { recursive: true })
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.SPORTHUB_E2E_PLAYWRIGHT || 'playwright')
const server = spawn(process.execPath, [path.join(root, 'scripts/performance/ui-server.mjs'), directory], {
  windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, NODE_ENV: 'production', GATEWAY_URL: 'http://127.0.0.1:1' },
})
let browser, logs = ''
try {
  const base = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Production UI fixture did not start')), 55000)
    server.on('error', reject)
    server.on('exit', code => { clearTimeout(timer); reject(Error(`Fixture server exited ${code}: ${logs.slice(-2000)}`)) })
    server.stderr.on('data', data => { logs = (logs + data).slice(-20000) })
    server.stdout.on('data', data => {
      logs = (logs + data).slice(-20000)
      for (const line of logs.split('\n')) {
        try { const value = JSON.parse(line); if (value.production && value.url) { clearTimeout(timer); resolve(value.url) } } catch { /* Next startup text. */ }
      }
    })
  })
  browser = await chromium.launch({ headless: true, channel: process.env.SPORTHUB_E2E_BROWSER_CHANNEL || 'chrome' })
  const context = await browser.newContext({ locale: 'vi-VN', timezoneId: 'Asia/Ho_Chi_Minh', viewport: { width: 1440, height: 1000 } })
  const f = fixture('OWNER'); f.user.roles.push('CUSTOMER')
  const court = f.courts[0], rules = [], policyRequests = [], quoteRequests = [], holds = [], drafts = []
  const day = new Date(Date.now() + 86400000).toLocaleDateString('en-CA', { timeZone: facility.timezone })
  const at = time => new Date(`${day}T${time}:00+07:00`).toISOString()
  let policy = { enabled: false, version: 0, selectionStepMinutes: 30, minimumMinutes: 60, maximumMinutes: 240 }
  let lostPublishReply = false
  const cells = [ ['15:00', '15:30', 50000], ['15:30', '16:00', 50000], ['16:00', '16:30', 100000], ['16:30', '17:00', 100000] ]
    .map(([from, to, amount]) => ({ startsAt: at(from), endsAt: at(to), amount, currency: 'VND', state: 'AVAILABLE', reason: null, ruleId: id(55), ruleVersion: 0 }))
  let quote
  await context.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), uri = url.pathname.replace('/api/v1', ''), method = request.method()
    const reply = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data, message: 'Dữ liệu thử' }) })
    if (uri === `/schedules/facilities/${facility.id}/booking-policy`) {
      if (method === 'PUT') {
        const input = request.postDataJSON(), key = request.headers()['idempotency-key']
        policyRequests.push({ input, key })
        assert.equal(input.selectionStepMinutes, 30); assert.equal(input.minimumMinutes, 60); assert.equal(input.maximumMinutes, 240)
        if (!lostPublishReply) { policy = { ...input, version: 1 }; lostPublishReply = true; await route.abort('connectionreset'); return }
        assert.equal(key, policyRequests[0].key); assert.deepEqual(input, policyRequests[0].input)
      }
      return reply(policy)
    }
    if (uri === `/schedules/facilities/${facility.id}/pricing`) {
      if (method === 'POST') {
        const input = request.postDataJSON()
        assert.equal(input.pricingUnit, 'HOURLY'); assert.equal(input.pricePerSlot, null)
        rules.push({ ...input, id: id(60 + rules.length), pricePerSlot: input.pricePerHour, active: true, version: 0 })
      }
      return reply(method === 'GET' ? rules : rules.at(-1))
    }
    if (uri === `/schedules/facilities/${facility.id}/preview/pricing`) {
      const input = request.postDataJSON(); drafts.push(input)
      assert.equal(input.rule.pricingUnit, 'HOURLY'); assert.equal(input.rule.pricePerHour, 100000); assert.equal(input.rule.pricePerSlot, null)
      return reply({ facilityId: facility.id, courtId: court.id, date: day, timezone: facility.timezone,
        pricingMode: 'HOURLY_INTERVAL_V1', slots: cells.slice(0, 2).map(slot => ({ ...slot, state: 'ELIGIBLE' })) })
    }
    if (uri === `/facilities/${facility.id}/refund-policy`) return reply(refundPolicy)
    if (uri === '/bookings/availability') return reply({ facilityId: facility.id, courtId: court.id, date: day, timezone: facility.timezone,
      pricingMode: 'HOURLY_INTERVAL_V1', selectionStepMinutes: 30, minimumMinutes: 60, maximumMinutes: 240, policyVersion: 1, slots: cells })
    if (uri === '/bookings/quote') {
      const input = request.postDataJSON(); quoteRequests.push(input)
      assert.equal(input.startsAt, at('15:30')); assert.equal(input.endsAt, at('16:30'))
      quote = { ...input, facilityId: facility.id, amount: 150000, currency: 'VND', timezone: facility.timezone,
        quoteVersion: 2, pricingMode: 'HOURLY_INTERVAL_V1', policyVersion: 1, quoteHash: 'a'.repeat(64), durationMinutes: 60,
        segments: cells.slice(1, 3),
        warnings: [{ code: 'SHORT_GAP_BEFORE', startsAt: at('15:00'), endsAt: at('15:30') }],
        suggestions: [{ courtId: court.id, startsAt: at('15:00'), endsAt: at('17:00') }] }
      return reply(quote)
    }
    if (uri === '/bookings/holds' && method === 'POST') {
      const input = request.postDataJSON(); holds.push(input)
      assert.equal(input.expectedAmount, 150000); assert.equal(input.quoteHash, quote.quoteHash)
      return reply({ id: id(70), holderId: f.user.id, courtId: court.id, facilityId: facility.id, startsAt: input.startsAt,
        endsAt: input.endsAt, expiresAt: new Date(Date.now() + 600000).toISOString(), state: 'HOLD', quote })
    }
    if (uri === `/bookings/holds/${id(70)}`) return reply({ id: id(70), holderId: f.user.id, courtId: court.id, facilityId: facility.id,
      startsAt: quote.startsAt, endsAt: quote.endsAt, expiresAt: new Date(Date.now() + 600000).toISOString(), state: 'HOLD', quote })
    return f.route(route)
  })
  const page = await context.newPage(), pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.goto(`${base}/owner/facilities/${facility.id}/pricing`)
  await page.getByRole('heading', { name: 'Bảng giá', exact: true }).waitFor()
  async function addRule(label, from, to, amount) {
    await page.getByLabel('Tên quy tắc', { exact: true }).fill(label)
    await page.locator('select[name="pricingUnit"]').selectOption('HOURLY')
    await page.getByLabel('Giá theo giờ (đồng/giờ)', { exact: true }).fill(String(amount))
    await page.getByLabel('Từ giờ', { exact: true }).fill(from)
    await page.getByLabel('Đến giờ', { exact: true }).fill(to)
    await page.getByLabel('Hiệu lực từ', { exact: true }).fill(day)
    if (rules.length === 0) {
      await page.getByLabel('Ngày xem dự thảo', { exact: true }).fill(day)
      await page.getByRole('button', { name: 'Xem giá dự thảo (không lưu)', exact: true }).click()
      await page.getByRole('heading', { name: 'Giá dự thảo, chưa lưu cấu hình', exact: true }).waitFor()
      assert.equal(rules.length, 0); assert.equal(drafts.length, 1)
    }
    await page.getByRole('button', { name: 'Thêm quy tắc giá', exact: true }).click()
    await page.getByRole('heading', { name: new RegExp(label) }).waitFor()
  }
  await addRule('Giá trước 16 giờ', '15:00', '16:00', 100000)
  await addRule('Giá từ 16 giờ', '16:00', '17:00', 200000)
  await page.getByRole('button', { name: 'Kiểm tra giá và bật đặt giờ tự chọn', exact: true }).click()
  await page.getByRole('button', { name: 'Thử lại yêu cầu trước', exact: true }).click()
  await page.getByRole('button', { name: 'Tắt đặt giờ tự chọn', exact: true }).waitFor()
  assert.equal(policyRequests.length, 2)
  await page.screenshot({ path: path.join(output, 'owner-hourly-pricing.png'), fullPage: true })
  f.user.id = id(99); f.user.roles = ['CUSTOMER']
  await page.goto(`${base}/facilities/${facility.id}/courts/${court.id}?date=${day}`)
  await page.getByRole('button', { name: /15:30–16:00/ }).click()
  assert.equal(await page.getByRole('button', { name: 'Xem tóm tắt và giữ chỗ', exact: true }).isDisabled(), true)
  await page.getByRole('button', { name: /16:00–16:30/ }).click()
  await page.locator('aside').getByText(/150\.000\s*₫/).waitFor()
  await page.getByText(/Khoảng chọn để lại giờ trống dưới 60 phút/).waitFor()
  assert.equal(holds.length, 0)
  await page.screenshot({ path: path.join(output, 'customer-hourly-quote.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false)
  await page.screenshot({ path: path.join(output, 'customer-hourly-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: 'Xem tóm tắt và giữ chỗ', exact: true }).click()
  await page.waitForURL(`**/customer/booking/holds/${id(70)}`)
  assert.equal(holds.length, 1); assert.equal(quoteRequests.length, 1)
  assert.deepEqual(pageErrors, []); assert.deepEqual(f.unknown, [])
  const result = { productionBuild: directory, backend: 'deterministic UI fixtures; PostgreSQL authority is tested separately',
    checks: ['explicit hourly Owner rates and unsaved draft preview', 'activation replay after response loss', '30-minute minimum rejection in UI',
      '150000 server quote', 'advisory orphan gap', 'no implicit hold', 'one hold with accepted quote hash'] }
  writeFileSync(path.join(output, 'result.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result))
} finally {
  await browser?.close()
  server.kill()
}
