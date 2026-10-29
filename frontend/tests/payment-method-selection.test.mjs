import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import vm from 'node:vm'
import test from 'node:test'
import ts from 'typescript'

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)))
const compile = new Map()

function harness({
  user = { id: 'staff', roles: ['STAFF', 'CUSTOMER'] },
  api = async () => ({}),
  resource = () => undefined,
  searchParams = '',
  router = { push() { } },
} = {}) {
  const hooks = new Map(), cache = new Map()
  let active = [], cursor = 0
  const jsx = (type, props, key) => ({ type, props, key })
  const react = {
    useState(initial) {
      const index = cursor++
      if (!(index in active)) active[index] = typeof initial === 'function' ? initial() : initial
      const slots = active
      return [
        slots[index],
        (next) => {
          slots[index] = typeof next === 'function' ? next(slots[index]) : next
        },
      ]
    },
    useRef(initial) {
      const index = cursor++
      if (!(index in active)) active[index] = { current: initial }
      return active[index]
    },
    useMemo(factory, dependencies) {
      const index = cursor++
      if (
        !(index in active) ||
        dependencies?.some((value, i) => !Object.is(value, active[index].dependencies[i]))
      )
        active[index] = { dependencies, value: factory() }
      return active[index].value
    },
    useEffect() { },
  }
  const stub = {
    react,
    'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    'next/link': { default: 'a' },
    'next/image': { default: 'img' },
    'next/navigation': {
      useRouter: () => router,
      useSearchParams: () => new URLSearchParams(searchParams),
    },
    '@/lib/api': { api, ApiError: class extends Error { } },
    '@/lib/auth-context': { useAuth: () => ({ user, ready: true }) },
    '@/lib/use-polling': { usePolling() { } },
    '@/lib/use-api': {
      useApi: (url) => ({
        data: resource(url),
        loading: false,
        error: '',
        reload() { },
      }),
    },
    '@/features/auth/require-auth': { RequireAuth: ({ children }) => children },
    '@/components/ui/input': { Input: 'input' },
    '@/components/ui/button': { Button: 'button' },
    '@/features/schedule/types': { money: (value, currency) => `${value} ${currency || 'VND'}` },
    './countdown': { Countdown: 'countdown', useRemaining: () => 600 },
    '@/features/booking/countdown': { Countdown: 'countdown', useRemaining: () => 600 },
    './refund-policy-notice': { RefundPolicyNotice: 'refund-notice' },
    './own-facility-notice': { OwnFacilityNotice: 'own-notice' },
    './counter-cash-payment': { CashPaymentResult: 'cash-result', CounterCashPayment: 'counter-cash' },
    '@/features/home/home-art': { CourtLines: () => null },
    'lucide-react': new Proxy({}, { get: (_, name) => String(name) }),
  }
  function load(relative) {
    const file = path.resolve(root, relative)
    if (cache.has(file)) return cache.get(file)
    const exports = {}
    cache.set(file, exports)
    if (!compile.has(file))
      compile.set(
        file,
        ts.transpileModule(readFileSync(file, 'utf8'), {
          fileName: file,
          compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
        }).outputText,
      )
    vm.runInNewContext(
      compile.get(file),
      {
        exports,
        Error,
        FormData: class { constructor(e) { this.e = e } get(k) { return this.e?.get ? this.e.get(k) : null } },
        crypto: { randomUUID: () => 'uuid' },
        require: (name) => {
          if (name in stub) return stub[name]
          let full = name.startsWith('@/') ? path.join(root, name.slice(2)) : path.resolve(path.dirname(file), name)
          if (!existsSync(full)) {
            full += existsSync(full + '.ts') ? '.ts' : '.tsx'
          }
          return load(path.relative(root, full))
        },
      },
      { filename: file },
    )
    return exports
  }
  return {
    load,
    render(component, props) {
      active = hooks.get(component) ?? []
      hooks.set(component, active)
      cursor = 0
      let node = component(props)
      while (typeof node?.type === 'function') {
        const child = node.type
        active = hooks.get(child) ?? []
        hooks.set(child, active)
        cursor = 0
        node = child(node.props)
      }
      return node
    },
  }
}

function text(node) {
  if (!node) return ''
  if (Array.isArray(node)) return node.map(text).join(' ')
  if (typeof node === 'object' && node.props?.children) return text(node.props.children)
  return String(node)
}

function findNodes(node, predicate) {
  if (!node) return []
  const matches = predicate(node) ? [node] : []
  if (Array.isArray(node)) return node.flatMap((child) => findNodes(child, predicate))
  if (typeof node === 'object' && node.props?.children)
    return [...matches, ...findNodes(node.props.children, predicate)]
  return matches
}

test('HoldSummary renders payment method selection (Bank transfer and Cash)', () => {
  const h = harness({
    user: { id: 'staff', roles: ['STAFF', 'CUSTOMER'] },
    searchParams: 'counter=true',
    resource: (url) =>
      url === '/bookings/holds/held'
        ? {
          id: 'held',
          facilityId: 'facility',
          courtId: 'court',
          state: 'HOLD',
          startsAt: '2030-01-01T01:00:00Z',
          endsAt: '2030-01-01T02:00:00Z',
          expiresAt: '2030-01-01T00:50:00Z',
          quote: { amount: 120000, currency: 'VND', segments: [] },
        }
        : url === '/facilities/facility'
          ? { id: 'facility', name: 'Sân test', ownerId: 'owner' }
          : undefined,
  })

  const comp = h.load('features/booking/hold-summary.tsx').HoldSummary
  const tree = h.render(comp, { id: 'held' })
  const renderedText = text(tree)

  assert.ok(renderedText.includes('Hình thức thanh toán'), 'Should display payment method label')
  assert.ok(renderedText.includes('Chuyển khoản ngân hàng'), 'Should include bank transfer option')
  assert.ok(renderedText.includes('Tiền mặt'), 'Should include cash option')

  // In counter booking, cash option button is enabled
  const buttons = findNodes(tree, (n) => n.type === 'button')
  const cashButton = buttons.find((b) => text(b).includes('Tiền mặt'))
  assert.ok(cashButton, 'Cash button exists')
  assert.equal(Boolean(cashButton.props.disabled), false, 'Cash option is enabled in counter mode')
})

test('HoldSummary disables cash option when not in counter mode', () => {
  const h = harness({
    user: { id: 'customer', roles: ['CUSTOMER'] },
    searchParams: '',
    resource: (url) =>
      url === '/bookings/holds/held'
        ? {
          id: 'held',
          facilityId: 'facility',
          courtId: 'court',
          state: 'HOLD',
          startsAt: '2030-01-01T01:00:00Z',
          endsAt: '2030-01-01T02:00:00Z',
          expiresAt: '2030-01-01T00:50:00Z',
          quote: { amount: 120000, currency: 'VND', segments: [] },
        }
        : url === '/facilities/facility'
          ? { id: 'facility', name: 'Sân test', ownerId: 'owner' }
          : undefined,
  })

  const comp = h.load('features/booking/hold-summary.tsx').HoldSummary
  const tree = h.render(comp, { id: 'held' })
  const buttons = findNodes(tree, (n) => n.type === 'button')
  const cashButton = buttons.find((b) => text(b).includes('Tiền mặt'))
  assert.ok(cashButton, 'Cash button exists')
  assert.equal(Boolean(cashButton.props.disabled), true, 'Cash option is disabled when not counter')
})

test('HoldSummary cash payment in counter booking calls counter-cash and navigates to cash payment', async () => {
  const calls = []
  const destinations = []
  const h = harness({
    user: { id: 'staff', roles: ['STAFF', 'CUSTOMER'] },
    searchParams: 'counter=true',
    router: { push: (url) => destinations.push(url) },
    api: async (url, opts) => {
      calls.push({ url, opts })
      if (url.startsWith('/bookings?counter=true')) return { id: 'b123' }
      if (url === '/payments') return { id: 'p123' }
      if (url === '/payments/p123/counter-cash') return { id: 'cash123', provider: 'CASH' }
      return {}
    },
    resource: (url) =>
      url === '/bookings/holds/held'
        ? {
          id: 'held',
          facilityId: 'facility',
          courtId: 'court',
          state: 'HOLD',
          startsAt: '2030-01-01T01:00:00Z',
          endsAt: '2030-01-01T02:00:00Z',
          expiresAt: '2030-01-01T00:50:00Z',
          quote: { amount: 120000, currency: 'VND', segments: [] },
        }
        : url === '/facilities/facility'
          ? { id: 'facility', name: 'Sân test', ownerId: 'owner' }
          : undefined,
  })

  const comp = h.load('features/booking/hold-summary.tsx').HoldSummary
  let tree = h.render(comp, { id: 'held' })

  // Select cash
  const cashButton = findNodes(tree, (n) => n.type === 'button' && text(n).includes('Tiền mặt'))[0]
  cashButton.props.onClick()

  // Re-render after state change
  tree = h.render(comp, { id: 'held' })

  // Submit form
  const form = findNodes(tree, (n) => n.type === 'form')[0]
  const fakeFormData = new Map([
    ['guestName', 'Nguyen Van A'],
    ['guestPhone', '0912345678'],
  ])
  await form.props.onSubmit({
    preventDefault() { },
    currentTarget: {
      get(k) {
        return fakeFormData.get(k)
      },
    },
  })

  // Verify API calls
  assert.ok(calls.some((c) => c.url.includes('/bookings?counter=true')), 'Created booking')
  assert.ok(calls.some((c) => c.url === '/payments'), 'Created payment')
  assert.ok(calls.some((c) => c.url === '/payments/p123/counter-cash'), 'Called counter-cash')
  assert.deepEqual(destinations, ['/customer/payments/cash123'], 'Navigated to cash receipt')
})

test('BankPaymentInstructions has no "Thanh toán bằng tiền mặt" button and displays redesigned instructions', () => {
  const h = harness({
    user: { id: 'buyer', roles: ['CUSTOMER'] },
    resource: (url) =>
      url === '/payments/p123/qr'
        ? {
          state: 'READY',
          amount: '120000',
          amountReceived: '0',
          accountNumber: '0356736410',
          accountName: 'NGUYEN MINH SANG',
          description: 'SC13',
          qrCode: 'qr-code-data',
          expiresAt: '2030-01-01T00:50:00Z',
          counterCashAllowed: true,
        }
        : undefined,
  })

  const comp = h.load('features/payments/bank-payment-instructions.tsx').BankPaymentInstructions
  const tree = h.render(comp, {
    payment: { id: 'p123', amount: 120000, currency: 'VND', purpose: 'BOOKING' },
  })

  const renderedText = text(tree)
  assert.equal(renderedText.includes('Thanh toán bằng tiền mặt'), false, 'Must NOT contain "Thanh toán bằng tiền mặt"')
  assert.ok(renderedText.includes('Chuyển khoản cho cơ sở'), 'Contains heading')
  assert.ok(renderedText.includes('0356736410'), 'Contains account number')
  assert.ok(renderedText.includes('NGUYEN MINH SANG'), 'Contains account holder')
  assert.ok(renderedText.includes('SC13'), 'Contains memo')
  assert.ok(renderedText.includes('Kiểm tra giao dịch'), 'Contains refresh button')
})
