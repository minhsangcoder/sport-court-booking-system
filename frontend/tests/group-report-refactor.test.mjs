import assert from 'node:assert/strict'
import test from 'node:test'
import * as jsx from 'react/jsx-runtime'
import { renderToStaticMarkup } from 'react-dom/server'
import { moduleLoader, hookDriver, flush } from './support/load-module.mjs'

const plain = value => JSON.parse(JSON.stringify(value))
const ui = { 'react/jsx-runtime': jsx, 'next/link': { default: 'a' }, '@/components/ui/button': { Button: 'button' }, '@/components/ui/input': { Input: 'input' } }
const user = { id: 'owner', roles: ['CUSTOMER'] }
class Form extends FormData { constructor(values) { super(); for (const [key, value] of values) this.set(key, value) } }

test('hidden-group preferences react immediately and stay scoped to the current user across account changes', () => {
  const storage = new Map(), listeners = new Map(), driver = hookDriver()
  const window = { addEventListener(type, fn) { const values = listeners.get(type) ?? new Set(); values.add(fn); listeners.set(type, values) }, removeEventListener(type, fn) { listeners.get(type)?.delete(fn) }, dispatchEvent(event) { for (const fn of listeners.get(event.type) ?? []) fn() } }
  const load = moduleLoader({ window, localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) } }, { react: driver.react })
  const hook = load('features/groups/hidden-groups.ts').useHiddenGroups
  driver.render(hook, 'A').hide('group-1'); assert.deepEqual(plain(driver.render(hook, 'A').hiddenIds), ['group-1'])
  assert.deepEqual(plain(driver.render(hook, 'B').hiddenIds), [])
  driver.render(hook, 'B').hide('group-2'); assert.deepEqual(plain(driver.render(hook, 'B').hiddenIds), ['group-2'])
  driver.render(hook, 'A').unhide('group-1'); assert.deepEqual(plain(driver.render(hook, 'A').hiddenIds), [])
  storage.set('sporthub_hidden_groups_A', 'invalid-json'); window.dispatchEvent(new Event('storage'))
  assert.deepEqual(plain(driver.render(hook, 'A').hiddenIds), []); driver.unmount(); assert.equal(listeners.get('storage').size, 0)
})

test('the moved group-reuse form preserves request keys on retry and rotates them only when the form changes', async () => {
  const driver = hookDriver(), requests = [], routes = []
  const root = { id: 'kept', owner_id: user.id, state: 'KEPT', members: [], version: 4 }
  const load = moduleLoader({ FormData: Form, fetch: async (path, options) => { requests.push({ path, ...options }); return Response.json({ data: { bookingGroup: { id: 'new-group' } } }) } }, {
    ...ui, react: driver.react, '@/lib/auth-context': { useAuth: () => ({ user }) },
    '@/lib/use-api': { useApi: () => ({ data: [], reload: async () => {} }) }, '@/lib/use-polling': { usePolling() {} },
    'next/navigation': { useRouter: () => ({ push: path => routes.push(path) }) },
  })
  const hook = load('features/groups/use-play-workspace.ts').usePlayWorkspace
  const props = { root, completed: true, terminal: true, reload() {} }
  const view = load('features/groups/group-reuse-form.tsx').GroupReuseForm
  const form = () => view({ model: driver.render(hook, props) })
  const event = { preventDefault() {}, currentTarget: [['holdId', 'new-hold'], ['name', 'Cuộc chơi mới'], ['maxMembers', '5']] }
  form().props.onSubmit(event); await flush(); form().props.onSubmit(event); await flush()
  assert.equal(requests[0].path, '/api/v1/groups/play-groups/kept/reuse')
  assert.deepEqual(JSON.parse(requests[0].body), { holdId: 'new-hold', name: 'Cuộc chơi mới', maxMembers: 5 })
  assert.equal(requests[0].headers.get('Idempotency-Key'), requests[1].headers.get('Idempotency-Key'))
  form().props.onChange(); form().props.onSubmit(event); await flush()
  assert.notEqual(requests[0].headers.get('Idempotency-Key'), requests[2].headers.get('Idempotency-Key')); assert.deepEqual(routes, Array(3).fill('/customer/groups/new-group'))
  driver.unmount()
})

test('report filters validate ranges, reset pagination and use server totals independently of the displayed page', () => {
  const driver = hookDriver(), paths = [], listeners = new Map(), reloads = []
  const money = { courtCollected: '120000', transferFees: '5000', operatingRevenue: '125000', policyRefunded: '80000', afterRefund: '45000', transferCollected: '100000', sellerPaid: '0', sellerDue: '95000', refundDue: '0', technicalRefunded: '0' }
  let availability = 'PARTIAL', failure = ''
  const report = () => ({ mode: 'DEMO', moneyAvailability: availability, summary: { bookingCount: 1, fullyPaidBookingCount: 1, cancelledCount: 1, money }, transactions: { items: [], total: 0 } })
  const load = moduleLoader({ window: { addEventListener: (type, action) => listeners.set(type, action), removeEventListener: type => listeners.delete(type) } }, {
    ...ui, react: driver.react,
    '@/features/facility/facility-store': { useFacility: () => ({ facility: { id: 'A', timezone: 'Asia/Ho_Chi_Minh' } }) },
    '@/lib/use-api': { useApi: path => { paths.push(path); return { data: report(), loading: false, error: failure, reload: () => reloads.push('report') } } },
  })
  const hook = load('features/facility/report/use-facility-report.ts').useFacilityReport
  let model = driver.render(hook); const initial = model.range
  model.setPage(3); model = driver.render(hook); assert.match(paths.at(-1), /page=3&size=25&mode=REAL&moneySource=ALL&peerPage=0$/)
  model.apply({ fromDate: '2026-10-23', toDate: '2026-10-22' }); model = driver.render(hook)
  assert.ok(model.error); assert.deepEqual(model.range, initial)
  model.apply({ fromDate: '2026-10-20', toDate: '2026-10-23' }); model = driver.render(hook)
  assert.equal(model.page, 0); assert.match(paths.at(-1), /fromDate=2026-10-20&toDate=2026-10-23/)
  const kpis = load('features/facility/report/report-kpis.tsx').ReportKpis
  assert.match(renderToStaticMarkup(kpis({ model })), /120\.000/); assert.doesNotMatch(renderToStaticMarkup(kpis({ model })), /45\.000|Đã hoàn theo chính sách|Khoản thu sau hoàn/)
  listeners.get('sporthub:demo-money-changed')()
  listeners.get('focus')()
  listeners.get('storage')({ key: 'unrelated' })
  listeners.get('storage')({ key: 'sporthub:demo-money-changed' })
  assert.equal(reloads.length, 3)
  availability = 'UNAVAILABLE'; model = driver.render(hook); assert.equal(model.amount('0'), 'Chưa có dữ liệu')
  assert.doesNotMatch(renderToStaticMarkup(kpis({ model })), /120\.000/)
  failure = 'Không thể tải báo cáo.'; assert.equal(driver.render(hook).report, null)
  driver.unmount(); assert.equal(listeners.size, 0)
})

test('report loads its server-selected REAL mode directly and keeps explicit DEMO selection separate', () => {
  const driver = hookDriver(), paths = []
  let loading = true, data
  const load = moduleLoader({ window: { addEventListener() {}, removeEventListener() {} } }, {
    ...ui, react: driver.react,
    '@/features/facility/facility-store': { useFacility: () => ({ facility: { id: 'A', timezone: 'Asia/Ho_Chi_Minh' } }) },
    '@/lib/use-api': { useApi: path => { paths.push(path); return { data, loading, error: '', reload() {} } } },
  })
  const hook = load('features/facility/report/use-facility-report.ts').useFacilityReport
  let model = driver.render(hook)
  assert.match(paths.at(-1), /^\/bookings\/reports\/facility\/A\/dashboard\?/)
  assert.match(paths.at(-1), /mode=REAL&moneySource=ALL/)
  assert.equal(model.mode, undefined)
  assert.equal(model.report, null)
  const filters = load('features/facility/report/report-filters.tsx').ReportFilters
  const pending = renderToStaticMarkup(filters({ model }))
  assert.match(pending, /Tất cả/)
  assert.doesNotMatch(pending, /<select[^>]*disabled/)
  data = { mode: 'REAL', moneyAvailability: 'COMPLETE' }; loading = false
  model = driver.render(hook); assert.equal(model.mode, 'REAL')
  model.setPage(3); model.setPeerPage(2); model = driver.render(hook)
  assert.match(paths.at(-1), /page=3&size=25&mode=REAL&moneySource=ALL&peerPage=2$/)
  model.setMoneySource('CASH'); loading = true; model = driver.render(hook)
  assert.match(paths.at(-1), /page=0&size=25&mode=REAL&moneySource=CASH&peerPage=0$/)
  assert.equal(model.moneySource, 'CASH'); assert.equal(model.report, null)
  data = { mode: 'REAL', moneyAvailability: 'PARTIAL' }; loading = false
  model = driver.render(hook); assert.equal(model.report.mode, 'REAL')
  assert.ok(paths.every(path => path.startsWith('/bookings/reports/')))
  driver.unmount()
})
