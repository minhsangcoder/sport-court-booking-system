import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import vm from 'node:vm'
import { randomUUID } from 'node:crypto'
import test from 'node:test'
import ts from 'typescript'

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)))
const compile = new Map()
function harness({
  user = { id: 'staff', roles: ['STAFF', 'CUSTOMER'] },
  api = async () => ({}),
  resource = () => undefined,
  globals = {},
  searchParams = '',
} = {}) {
  const hooks = new Map(),
    cache = new Map()
  let active, cursor, visited
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
        dependencies.some((value, i) => !Object.is(value, active[index].dependencies[i]))
      )
        active[index] = { dependencies, value: factory() }
      return active[index].value
    },
    useEffect() {},
  }
  const stub = {
    react,
    'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    'next/link': { default: 'a' },
    'next/image': { default: 'img' },
    'next/dynamic': { default: () => () => null },
    '@/lib/use-polling': { usePolling() {} },
    'next/navigation': {
      useRouter: () => ({ push() {} }),
      useSearchParams: () => new URLSearchParams(searchParams),
    },
    '@/lib/api': { api },
    '@/lib/auth-context': { useAuth: () => ({ user, ready: true }) },
    '@/lib/use-api': {
      useApi: (url) => ({
        data: resource(url),
        loading: false,
        error: '',
        reload() {
          globals.reload?.(url)
        },
      }),
    },
    '@/features/auth/require-auth': { RequireAuth: ({ children }) => children },
    '@/components/ui/input': { Input: 'input' },
    '@/components/ui/button': { Button: 'button' },
    '@/features/schedule/types': { money: (value, currency) => `${value} ${currency}` },
    '@/features/operations/qr-checkin': {
      QrUploadButton: 'qr-upload',
      QuickQrCheckinModal: 'qr-modal',
    },
    './qr-checkin': { QrUploadButton: 'qr-upload', QuickQrCheckinModal: 'qr-modal' },
    './group': { StaffGroupAttendance: 'attendance' },
    './countdown': { Countdown: 'countdown', useRemaining: () => 600 },
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
        URL,
        URLSearchParams,
        Headers,
        FormData,
        Response,
        crypto: { randomUUID },
        setTimeout: (callback) => {
          callback()
          return 1
        },
        setInterval: () => 1,
        clearInterval() {},
        ...globals,
        require(name) {
          if (Object.hasOwn(stub, name)) return stub[name]
          let resolved = name.startsWith('@/')
            ? path.resolve(root, name.slice(2))
            : path.resolve(path.dirname(file), name)
          if (!existsSync(resolved)) resolved += existsSync(resolved + '.ts') ? '.ts' : '.tsx'
          return load(path.relative(root, resolved))
        },
      },
      { filename: file },
    )
    return exports
  }
  function visit(node, key) {
    if (node == null || typeof node !== 'object') return node
    if (Array.isArray(node)) return node.map((child, index) => visit(child, key + '/' + index))
    if (typeof node.type === 'function') {
      const id = key + ':' + node.type.name + ':' + (node.key ?? '')
      visited.add(id)
      active = hooks.get(id) || []
      hooks.set(id, active)
      cursor = 0
      return visit(node.type(node.props), id)
    }
    return {
      ...node,
      props: { ...node.props, children: visit(node.props?.children, key + '/children') },
    }
  }
  return {
    load,
    render(component, props) {
      visited = new Set()
      const tree = visit(jsx(component, props), 'root')
      for (const key of hooks.keys()) if (!visited.has(key)) hooks.delete(key)
      return tree
    },
  }
}
function nodes(tree, predicate) {
  if (tree == null || typeof tree !== 'object') return []
  if (Array.isArray(tree)) return tree.flatMap((child) => nodes(child, predicate))
  return [...(predicate(tree) ? [tree] : []), ...nodes(tree.props?.children, predicate)]
}
function text(tree) {
  if (tree == null || tree === false) return ''
  if (typeof tree !== 'object') return String(tree)
  if (Array.isArray(tree)) return tree.map(text).join(' ')
  return text(tree.props?.children)
}
const event = { preventDefault() {} }
const flush = () => new Promise((resolve) => setImmediate(resolve))
const booking = (id = 'booking-A', overrides = {}) => ({
  id,
  facilityId: 'facility-A',
  createdBy: 'staff',
  customerId: null,
  currentHolderId: null,
  source: 'OFFLINE_COUNTER',
  status: 'CONFIRMED',
  guestName: 'Synthetic Guest',
  guestPhone: '0911223344',
  bookingCode: 'ABCDEF1234',
  startsAt: '2026-10-21T10:00:00Z',
  endsAt: '2026-10-21T11:00:00Z',
  amount: 100000,
  currency: 'VND',
  priceSnapshot: { timezone: 'Asia/Ho_Chi_Minh', segments: [] },
  ...overrides,
})
const payment = (b, status = 'PENDING') => ({
  id: 'payment-' + b.id,
  bookingId: b.id,
  payerId: 'staff',
  purpose: 'BOOKING',
  provider: 'DEMO',
  status,
  amount: b.amount,
  currency: 'VND',
})

test('owner can reach check-in and counter booking from both account menus', () => {
  const h = harness()
  const model = {
    user: { fullName: 'Owner', roles: ['OWNER', 'CUSTOMER'] },
    isStaffOnly: false,
    isActive: () => false,
  }
  for (const [file, exported] of [
    ['header-account-menu', 'HeaderAccountMenu'],
    ['header-mobile-menu', 'HeaderMobileMenu'],
  ]) {
    const tree = h.render(h.load(`components/layout/${file}.tsx`)[exported], { model })
    for (const href of ['/owner/bookings', '/owner/counter-booking'])
      assert.equal(nodes(tree, (n) => n.type === 'a' && n.props.href === href).length, 1)
  }
})

test('counter toolbar uses the active owner or staff workspace and selected facility', () => {
  const h = harness()
  const { OperationToolbar } = h.load('features/operations/operation-toolbar.tsx')
  for (const owner of [true, false]) {
    const tree = h.render(OperationToolbar, {
      model: { owner, facilityId: 'facility-A', can: () => true },
    })
    assert.equal(
      nodes(tree, (n) => n.type === 'a' && text(n) === 'Đặt tại quầy')[0].props.href,
      `/${owner ? 'owner' : 'staff'}/counter-booking?facilityId=facility-A`,
    )
  }
})

test('owner counter chooser defaults to an owned facility and preserves it in court links', () => {
  const h = harness({
    user: { id: 'owner', roles: ['OWNER'] },
    searchParams: 'facilityId=someone-elses-facility',
    resource: (url) =>
      url === '/owner/facilities'
        ? [{ id: 'mine', name: 'My facility' }]
        : url === '/facilities/mine/courts'
          ? [{ id: 'court', name: 'Court' }]
          : undefined,
  })
  const tree = h.render(h.load('features/operations/counter-page.tsx').CounterPage, { owner: true })
  assert.equal(
    nodes(tree, (n) => n.type === 'a' && text(n) === 'Court')[0].props.href,
    '/owner/counter-booking/court?facilityId=mine',
  )
  assert.equal(nodes(tree, (n) => n.type === 'select')[0].props.value, 'mine')
})

test('owner personal availability is blocked with a counter link; other courts remain bookable', () => {
  for (const own of [true, false]) {
    const h = harness({
      user: { id: 'owner', roles: ['OWNER', 'CUSTOMER'] },
      resource: (url) =>
        url === '/facilities/facility-A'
          ? { ownerId: own ? 'owner' : 'someone-else' }
          : url?.startsWith('/bookings/availability?')
            ? {
                slots: [
                  {
                    startsAt: '2030-01-01T01:00:00Z',
                    endsAt: '2030-01-01T02:00:00Z',
                    state: 'AVAILABLE',
                    amount: 100000,
                  },
                ],
              }
            : undefined,
    })
    const tree = h.render(h.load('features/discovery/availability-page.tsx').AvailabilityPage, {
      facilityId: 'facility-A',
      courtId: 'court',
    })
    const slot = nodes(tree, (n) => n.type === 'button' && n.props['aria-pressed'] !== undefined)[0]
    assert.equal(Boolean(slot.props.disabled), own)
    assert.equal(text(tree).includes('Đây là cơ sở của bạn'), own)
    assert.equal(
      nodes(
        tree,
        (n) => n.type === 'a' && n.props.href === '/owner/counter-booking?facilityId=facility-A',
      ).length,
      own ? 1 : 0,
    )
  }
})

test('counter booking retains availability at own facility without obsolete refund promotion', () => {
  const h = harness({
    user: { id: 'owner', roles: ['OWNER', 'CUSTOMER'] },
    resource: (url) =>
      url === '/facilities/mine'
        ? { ownerId: 'owner' }
        : url?.startsWith('/bookings/availability?')
          ? { slots: [] }
          : undefined,
  })
  const tree = h.render(h.load('features/discovery/availability-page.tsx').AvailabilityPage, {
    facilityId: 'mine',
    courtId: 'court',
    counter: true,
  })
  assert.doesNotMatch(text(tree), /Đây là cơ sở của bạn|Chính sách hoàn của cơ sở khi đủ điều kiện/)
})

test('an old personal hold at own facility cannot be confirmed while counter checkout stays available', () => {
  for (const counter of [false, true]) {
    const h = harness({
      user: { id: 'owner', roles: ['OWNER', 'CUSTOMER'] },
      searchParams: counter ? 'counter=true' : '',
      resource: (url) =>
        url === '/bookings/holds/held'
          ? {
              id: 'held',
              facilityId: 'mine',
              courtId: 'court',
              state: 'HOLD',
              startsAt: '2030-01-01T01:00:00Z',
              endsAt: '2030-01-01T02:00:00Z',
              expiresAt: '2030-01-01T00:50:00Z',
              quote: {
                amount: 100000,
                currency: 'VND',
                segments: [],
                refundPolicy: { configured: true },
              },
            }
          : url === '/facilities/mine'
            ? { id: 'mine', ownerId: 'owner' }
            : url === '/owner/facilities'
              ? [{ id: 'mine' }]
              : undefined,
    })
    const tree = h.render(h.load('features/booking/hold-summary.tsx').HoldSummary, { id: 'held' })
    assert.equal(
      Boolean(
        nodes(tree, (n) => n.type === 'button' && n.props.type === 'submit')[0].props.disabled,
      ),
      !counter,
    )
    assert.equal(text(tree).includes('Đây là cơ sở của bạn'), !counter)
  }
})

test('owner counter slots require facility ownership, not merely the global role', () => {
  for (const allowed of [true, false]) {
    const h = harness({
      user: { id: 'owner', roles: ['OWNER'] },
      searchParams: 'facilityId=mine',
      resource: (url) => (url === '/owner/facilities/mine' && allowed ? { id: 'mine' } : undefined),
    })
    const tree = h.render(h.load('features/operations/counter-slots.tsx').CounterSlots, {
      courtId: 'court',
      owner: true,
    })
    assert.equal(text(tree).includes('Chọn giờ chơi'), allowed)
    assert.equal(text(tree).includes('Bạn chưa có quyền đặt tại quầy'), !allowed)
  }
})

test('manual check-in uses the real API prefix, normalizes codes and keeps signed QR separate', async () => {
  const calls = []
  const h = harness({
    globals: {
      fetch: async (url, options) => {
        calls.push({ url, body: JSON.parse(options.body) })
        return Response.json({ data: booking() })
      },
    },
  })
  // Load the actual API client as well as the operational helper.
  const client = h.load('lib/api.ts')
  const check = h.load('features/operations/check-in.ts')
  // Other component tests use a transport seam. This request test exercises the client directly.
  await client.api('/bookings/facility/facility-A/check-in-by-code', {
    method: 'POST',
    body: JSON.stringify({ bookingCode: 'ABCDEF1234' }),
  })
  const requests = []
  const normalized = harness({
    api: async (url, options) => {
      requests.push({ url, body: JSON.parse(options.body) })
      return booking()
    },
  }).load('features/operations/check-in.ts')
  await normalized.checkInBooking(booking(), ' abcdef1234 ')
  await normalized.checkInBooking(booking(), 'signed_payload.signature')
  assert.equal(calls[0].url, '/api/v1/bookings/facility/facility-A/check-in-by-code')
  assert.equal(requests[0].url, '/bookings/facility/facility-A/check-in-by-code')
  assert.deepEqual(requests[0].body, { bookingCode: 'ABCDEF1234' })
  assert.equal(requests[1].url, '/bookings/booking-A/check-in')
  assert.deepEqual(requests[1].body, { token: 'signed_payload.signature' })
  assert.throws(() => check.checkInBooking(booking(), 'bad code'), /10 ký tự/)
})

test('operational account detail hides the code, accepts customer input once and refreshes CHECKED_IN', async () => {
  let b = booking('booking-A', { currentHolderId: 'customer', customerId: 'customer' }),
    reloads = 0,
    release
  const requests = []
  const pending = new Promise((resolve) => {
    release = resolve
  })
  const h = harness({
    api: async (url, options) => {
      requests.push({ url, body: JSON.parse(options.body) })
      await pending
      b = { ...b, status: 'CHECKED_IN' }
      return b
    },
    resource: (url) =>
      url === '/users/me/access'
        ? {
            facilityBindings: [
              { facilityId: b.facilityId, permissions: ['BOOKING_READ', 'BOOKING_CHECK_IN'] },
            ],
          }
        : url?.endsWith('/operations')
          ? { detail: { booking: b, history: [] }, statusAudit: [] }
          : undefined,
    globals: {
      reload() {
        reloads++
      },
    },
  })
  const { OperationalDetail } = h.load('features/operations/operational-detail.tsx')
  let tree = h.render(OperationalDetail, { id: b.id })
  assert.match(text(tree), /Khách có tài khoản/)
  assert.match(text(tree), /Khách lúc đặt: Synthetic Guest/)
  assert.doesNotMatch(text(tree), /ABCDEF1234|Mã xác nhận vào sân vận hành/)
  assert.equal(
    nodes(tree, (n) => n.type === 'input' && n.props.name === 'token')[0].props.value,
    '',
  )
  nodes(tree, (n) => n.type === 'input' && n.props.name === 'token')[0].props.onChange({
    target: { value: 'abcdef1234' },
  })
  tree = h.render(OperationalDetail, { id: b.id })
  const submit = nodes(tree, (n) => n.type === 'form')[0].props.onSubmit
  submit(event)
  submit(event)
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/bookings/facility/facility-A/check-in-by-code')
  release()
  await flush()
  tree = h.render(OperationalDetail, { id: b.id })
  assert.equal(reloads, 1)
  assert.match(text(tree), /Đang chơi/)
  assert.equal(nodes(tree, (n) => n.type === 'form').length, 0)
})

test('read-only staff cannot read the operational code, check in or pay another operator booking', () => {
  const b = booking('readonly', { createdBy: 'another-staff', status: 'PENDING' })
  const h = harness({
    resource: (url) =>
      url === '/users/me/access'
        ? {
            facilityBindings: [
              {
                facilityId: b.facilityId,
                permissions: ['BOOKING_READ', 'BOOKING_PAYMENT_COUNTER'],
              },
            ],
          }
        : url?.endsWith('/operations')
          ? { detail: { booking: b, history: [] }, statusAudit: [] }
          : undefined,
  })
  const { OperationalDetail } = h.load('features/operations/operational-detail.tsx')
  const tree = h.render(OperationalDetail, { id: b.id })
  assert.doesNotMatch(text(tree), /ABCDEF1234|Xác nhận khách vãng lai đến sân/)
  assert.equal(nodes(tree, (n) => n.type === 'form').length, 0)
  assert.doesNotMatch(text(tree), /Thanh toán DEMO tại quầy/)
})

test('manual check-in permission failure stays visible and does not report success', async () => {
  const b = booking('booking-A', { currentHolderId: 'customer', customerId: 'customer' })
  let reloads = 0
  const h = harness({
    api: async () => {
      throw Error('403: Quyền check-in đã bị thu hồi')
    },
    resource: (url) =>
      url === '/users/me/access'
        ? {
            facilityBindings: [
              { facilityId: b.facilityId, permissions: ['BOOKING_READ', 'BOOKING_CHECK_IN'] },
            ],
          }
        : url?.endsWith('/operations')
          ? { detail: { booking: b, history: [] }, statusAudit: [] }
          : undefined,
    globals: {
      reload() {
        reloads++
      },
    },
  })
  const { OperationalDetail } = h.load('features/operations/operational-detail.tsx')
  let tree = h.render(OperationalDetail, { id: b.id })
  nodes(tree, (n) => n.type === 'input')[0].props.onChange({ target: { value: 'ABCDEF1234' } })
  tree = h.render(OperationalDetail, { id: b.id })
  nodes(tree, (n) => n.type === 'form')[0].props.onSubmit(event)
  await flush()
  tree = h.render(OperationalDetail, { id: b.id })
  assert.match(text(tree), /Quyền check-in đã bị thu hồi/)
  assert.equal(reloads, 0)
})

test('counter payment keys differ across bookings and actual detail shape confirms without false pending', async () => {
  const creates = [],
    messages = []
  let completed = 0,
    gets = 0,
    closed = 0
  const h = harness({
    api: async (url, options) => {
      if (url === '/payments') {
        creates.push({
          key: options.headers['Idempotency-Key'],
          id: JSON.parse(options.body).bookingId,
        })
        return payment(booking(JSON.parse(options.body).bookingId))
      }
      if (url.includes('/demo/complete')) {
        completed++
        return { status: 'SUCCESS', provider: 'DEMO' }
      }
      gets++
      return { booking: booking(url.split('/').at(-1)) }
    },
  })
  const { CounterPaymentModal } = h.load('features/operations/counter-payment-modal.tsx')
  const props = {
    onClose() {
      closed++
    },
    onSuccess: (message) => messages.push(message),
  }
  h.render(CounterPaymentModal, { ...props, isOpen: false, booking: null })
  for (const id of ['booking-A', 'booking-B']) {
    const tree = h.render(CounterPaymentModal, {
      ...props,
      isOpen: true,
      booking: booking(id, { status: 'PENDING' }),
    })
    await nodes(tree, (n) => n.type === 'form')[0].props.onSubmit(event)
    h.render(CounterPaymentModal, { ...props, isOpen: false, booking: null })
  }
  assert.equal(creates.length, 2)
  assert.notEqual(creates[0].key, creates[1].key)
  assert.equal(completed, 2)
  assert.equal(gets, 2)
  assert.equal(closed, 2)
  assert.ok(
    messages.every(
      (message) => message.includes('mô phỏng') && message.includes('đã được xác nhận'),
    ),
  )
})

test('payment waits for confirmation, retains the attempt across close/reopen, and blocks double submit', async () => {
  let creates = 0,
    completes = 0,
    gets = 0,
    ready = false,
    closed = 0
  const messages = []
  const b = booking('waiting', { status: 'PENDING' })
  const h = harness({
    api: async (url) => {
      if (url === '/payments') {
        creates++
        return payment(b)
      }
      if (url.includes('/demo/complete')) {
        completes++
        return { status: 'SUCCESS', provider: 'DEMO' }
      }
      gets++
      return { booking: { ...b, status: ready ? 'CONFIRMED' : 'PENDING' } }
    },
  })
  const { CounterPaymentModal } = h.load('features/operations/counter-payment-modal.tsx')
  const props = {
    onClose() {
      closed++
    },
    onSuccess: (msg) => messages.push(msg),
  }
  let tree = h.render(CounterPaymentModal, { ...props, isOpen: true, booking: b })
  const submit = nodes(tree, (n) => n.type === 'form')[0].props.onSubmit
  await Promise.all([submit(event), submit(event)])
  tree = h.render(CounterPaymentModal, { ...props, isOpen: true, booking: b })
  assert.match(text(tree), /đang chờ hệ thống xác nhận/)
  assert.equal(messages.length, 0)
  assert.equal(closed, 0)
  assert.equal(gets, 15)
  h.render(CounterPaymentModal, { ...props, isOpen: false, booking: b })
  ready = true
  tree = h.render(CounterPaymentModal, { ...props, isOpen: true, booking: b })
  await nodes(tree, (n) => n.type === 'form')[0].props.onSubmit(event)
  assert.equal(creates, 1)
  assert.equal(completes, 1)
  assert.equal(messages.length, 1)
  assert.equal(closed, 1)
})

test('a failed payment request retries the same key and backend permission errors remain visible', async () => {
  let first = true
  const keys = []
  const b = booking('retry', { status: 'PENDING' })
  const h = harness({
    api: async (url, options) => {
      if (url === '/payments') {
        keys.push(options.headers['Idempotency-Key'])
        if (first) {
          first = false
          throw Error('503: retry transport')
        }
        return payment(b)
      }
      if (url.includes('/demo/complete')) return { status: 'SUCCESS', provider: 'DEMO' }
      throw Error('403: Booking không còn trong phạm vi được phép')
    },
  })
  const { CounterPaymentModal } = h.load('features/operations/counter-payment-modal.tsx')
  const props = {
    isOpen: true,
    booking: b,
    onClose() {
      assert.fail('must remain open')
    },
    onSuccess() {
      assert.fail('must not report confirmed')
    },
  }
  let tree = h.render(CounterPaymentModal, props)
  await nodes(tree, (n) => n.type === 'form')[0].props.onSubmit(event)
  tree = h.render(CounterPaymentModal, props)
  await nodes(tree, (n) => n.type === 'form')[0].props.onSubmit(event)
  assert.equal(keys[0], keys[1])
  assert.match(text(h.render(CounterPaymentModal, props)), /403: Booking/)
})

test('payment results prioritize genuine ownership and choose the operational workspace by facility', () => {
  const h = harness()
  const { bookingResultPath, canPayBooking } = h.load('features/booking/ownership.ts')
  const staff = { id: 'staff', roles: ['STAFF', 'CUSTOMER'] },
    owner = { id: 'staff', roles: ['OWNER', 'CUSTOMER'] },
    b = booking()
  assert.equal(bookingResultPath(payment(b), b, staff), '/staff/bookings/booking-A')
  assert.equal(
    bookingResultPath(
      payment(b),
      { ...b, customerId: 'customer', currentHolderId: 'customer' },
      staff,
    ),
    '/staff/bookings/booking-A',
  )
  assert.equal(
    bookingResultPath(payment(b), { ...b, customerId: 'staff', currentHolderId: 'staff' }, staff),
    '/customer/bookings/booking-A',
  )
  assert.equal(bookingResultPath(payment(b), b, owner, ['facility-A']), '/owner/bookings/booking-A')
  assert.equal(
    bookingResultPath(payment(b), b, { ...owner, roles: ['OWNER', 'STAFF', 'CUSTOMER'] }, [
      'different-facility',
    ]),
    '/staff/bookings/booking-A',
  )
  assert.equal(bookingResultPath(payment(b), undefined, staff), null)
  assert.equal(
    bookingResultPath({ ...payment(b), purpose: 'GROUP_CONTRIBUTION' }, b, staff),
    '/customer/groups/booking-A',
  )
  assert.equal(
    bookingResultPath(
      { ...payment(b), purpose: 'TRANSFER', acquisitionId: 'acquisition' },
      b,
      staff,
    ),
    '/customer/transfers/acquisitions/acquisition',
  )
  assert.equal(canPayBooking(b, { id: 'another-staff', roles: ['STAFF'] }, false), false)
  assert.equal(canPayBooking({ ...b, currentHolderId: 'staff' }, staff, false), true)
})

test('the actual payment page renders the customer result for a self-linked staff customer', () => {
  const b = booking('self', { customerId: 'staff', currentHolderId: 'staff' }),
    p = payment(b, 'SUCCESS')
  const h = harness({
    resource: (url) =>
      url === '/payments/' + p.id ? p : url === '/bookings/' + b.id ? { booking: b } : undefined,
  })
  const { PaymentPage } = h.load('features/booking/payment-page.tsx')
  const tree = h.render(PaymentPage, { id: p.id })
  const result = nodes(tree, (n) => n.type === 'a' && text(n).includes('Xem kết quả'))[0]
  assert.equal(result.props.href, '/customer/bookings/self')
})

test('the operational list hides guest codes and uses the scoped guest attendance command', async () => {
  const b = booking()
  const requests = []
  const h = harness({
    api: async (url, options) => {
      requests.push({ url, body: options.body && JSON.parse(options.body) })
      return { ...b, status: 'CHECKED_IN' }
    },
    resource: (url) =>
      url === '/users/me/access'
        ? {
            facilityBindings: [
              { facilityId: b.facilityId, permissions: ['BOOKING_READ', 'BOOKING_CHECK_IN'] },
            ],
          }
        : url === '/facilities'
          ? [{ id: b.facilityId, name: 'Facility' }]
          : url?.startsWith('/bookings/management/search')
            ? { items: [b], total: 1, totalPages: 1 }
            : undefined,
  })
  const { OperationBookings } = h.load('features/operations/bookings-page.tsx')
  let tree = h.render(OperationBookings, {})
  assert.doesNotMatch(text(tree), /ABCDEF1234|Mã xác nhận vào sân vận hành/)
  assert.equal(nodes(tree, (n) => n.type === 'input' && n.props.name === 'token').length, 0)
  nodes(
    tree,
    (n) => n.type === 'button' && text(n).includes('Xác nhận khách vãng lai đến sân'),
  )[0].props.onClick()
  await flush()
  assert.equal(requests[0].url, '/bookings/booking-A/check-in-guest')
  assert.equal(requests[0].body, undefined)
})

test('personal detail keeps QR and transfer for real owners while hiding them for guest operators and history', () => {
  const cases = [
    { roles: ['CUSTOMER'], owner: 'staff', source: 'ONLINE', expected: true },
    { roles: ['CUSTOMER', 'STAFF'], owner: 'staff', source: 'OFFLINE_COUNTER', expected: true },
    { roles: ['CUSTOMER', 'STAFF'], owner: null, source: 'OFFLINE_COUNTER', expected: false },
    { roles: ['CUSTOMER'], owner: 'buyer', source: 'ONLINE', expected: false },
    { roles: ['STAFF'], owner: 'staff', source: 'ONLINE', expected: false },
  ]
  for (const example of cases) {
    const b = booking('personal', {
      source: example.source,
      currentHolderId: example.owner,
      customerId: example.owner,
      priceSnapshot: { moneyClass: 'REAL', timezone: 'Asia/Ho_Chi_Minh', segments: [] },
    })
    const h = harness({
      user: { id: 'staff', roles: example.roles },
      resource: (url) =>
        url === '/bookings/personal'
          ? {
              booking: b,
              history: [],
              checkinToken: 'signed_payload.signature',
              checkinQrSvg: '<svg/>',
            }
          : undefined,
    })
    const { BookingDetailPage } = h.load('features/booking/booking-detail.tsx')
    const tree = h.render(BookingDetailPage, { id: b.id })
    assert.equal(
      nodes(tree, (n) => n.type === 'img' && n.props.alt === 'QR xác nhận vào sân lượt đặt').length,
      example.expected ? 1 : 0,
    )
    assert.equal(
      nodes(tree, (n) => n.type === 'a' && n.props.href === '/customer/bookings/personal/transfer')
        .length,
      example.expected ? 1 : 0,
    )
    assert.equal(text(tree).includes('Mã xác nhận vào sân thủ công'), example.expected)
    assert.equal(text(tree).includes('signed_payload.signature'), false)
    assert.equal(text(tree).includes('Mã dùng khi nhập thủ công'), false)
    const back = nodes(tree, (n) => n.type === 'a' && text(n).includes('Lịch đặt /'))[0]
    assert.equal(
      back.props.href,
      !example.expected && example.roles.includes('STAFF')
        ? '/staff/bookings'
        : '/customer/bookings',
    )
  }
})

test('neither REAL nor DEMO paid bookings offer self cancellation or new legacy resale', () => {
  for (const moneyClass of ['REAL', 'DEMO']) {
    for (const transferred of [false, true]) {
      const b = booking('real-policy', {
        source: 'ONLINE',
        customerId: transferred ? 'seller' : 'buyer',
        currentHolderId: 'buyer',
        priceSnapshot: { moneyClass, timezone: 'Asia/Ho_Chi_Minh', segments: [] },
      })
      const h = harness({
        user: { id: 'buyer', roles: ['CUSTOMER'] },
        resource: (url) =>
          url === '/bookings/real-policy'
            ? { booking: b, history: [], resaleConsumed: transferred }
            : undefined,
      })
      const { BookingDetailPage } = h.load('features/booking/booking-detail.tsx'),
        tree = h.render(BookingDetailPage, { id: b.id })
      assert.equal(
        text(tree).includes('người đặt trực tiếp không áp dụng hoàn tiền khi tự hủy'),
        !transferred,
      )
      assert.equal(
        text(tree).includes('Lượt đặt nhận qua chuyển nhượng không áp dụng hoàn tiền khi tự hủy.'),
        transferred,
      )
      assert.equal(text(tree).includes('100% giá chuyển nhượng đã chốt'), !transferred)
      assert.equal(
        nodes(
          tree,
          (n) => n.type === 'a' && n.props.href === '/customer/bookings/real-policy/transfer',
        ).length,
        transferred || moneyClass === 'DEMO' ? 0 : 1,
      )
      assert.equal(
        nodes(tree, (n) => n.type === 'button' && text(n).includes('Hủy lượt đặt')).length,
        0,
      )
    }
  }
})
