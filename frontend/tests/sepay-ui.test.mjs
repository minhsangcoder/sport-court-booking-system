import test from 'node:test'
import assert from 'node:assert/strict'
import { moduleLoader, hookDriver } from './support/load-module.mjs'
const jsx = (type, props, key) => ({ type, props, key })
function nodes(tree) {
  return !tree
    ? []
    : Array.isArray(tree)
      ? tree.flatMap(nodes)
      : typeof tree === 'object'
        ? [tree, ...nodes(tree.props?.children)]
        : []
}
function text(tree) {
  return !tree
    ? ''
    : Array.isArray(tree)
      ? tree.map(text).join(' ')
      : typeof tree === 'object'
        ? text(tree.props?.children)
        : String(tree)
}
function listing(moneyClass) {
  const driver = hookDriver(),
    calls = []
  const load = moduleLoader(
    {
      FormData: class {
        get(name) {
          return name === 'price' ? '120000' : '2026-11-01T08:00'
        }
      },
    },
    {
      react: driver.react,
      'react/jsx-runtime': { jsx, jsxs: jsx },
      'next/link': { default: 'a' },
      'next/navigation': { useRouter: () => ({ push() {} }) },
      '@/lib/use-api': {
        useApi: (url) => ({
          data:
            url === '/bookings/booking'
              ? {
                  booking: {
                    amount: 120000,
                    startsAt: '2026-11-01T08:00:00Z',
                    currency: 'VND',
                    priceSnapshot: { moneyClass },
                  },
                }
              : null,
          reload() {},
        }),
      },
      '@/lib/api': {
        api: (...args) => {
          calls.push(args)
          throw Error('Unexpected legacy request')
        },
      },
      '@/lib/auth-context': { useAuth: () => ({ user: { id: 'A' } }) },
      '@/features/auth/require-auth': { RequireAuth: 'auth' },
      '@/components/ui/input': { Input: 'input' },
      '@/components/ui/button': { Button: 'button' },
      '@/features/booking/admission-notice': { AdmissionNotice: 'admission' },
      '@/features/payments/bank-destination-form': { BankDestinationForm: 'bank-form' },
    },
  )
  const wrapper = load('features/transfers/listing-form.tsx').ListingForm({ bookingId: 'booking' })
  return {
    driver,
    calls,
    render: () => driver.render(wrapper.props.children.type, wrapper.props.children.props),
  }
}
test('a DEMO booking cannot quote a new fee-based resale from the current form', () => {
  const h = listing('DEMO'),
    tree = h.render()
  assert.match(text(tree), /không có phí chuyển nhượng/)
  assert.equal(nodes(tree).filter((n) => n.type === 'form').length, 0)
  assert.equal(nodes(tree).filter((n) => n.type === 'bank-form').length, 0)
  assert.equal(text(tree).includes('giữ phí chủ cơ sở cấu hình'), false)
  assert.equal(h.calls.length, 0)
  h.driver.unmount()
})
test('a REAL resale requires A bank details and explains the entire B to A SePay payment', async () => {
  const h = listing('REAL'),
    tree = h.render()
  assert.equal(nodes(tree).filter((n) => n.type === 'bank-form').length, 1)
  assert.match(text(tree), /100% giá đã chốt trực tiếp cho bạn qua QR SePay/)
  assert.equal(text(tree).includes('giữ phí'), false)
  await nodes(tree)
    .find((n) => n.type === 'form')
    .props.onSubmit({ preventDefault() {}, currentTarget: {} })
  assert.match(text(h.render()), /Chọn tài khoản ngân hàng/)
  assert.equal(h.calls.length, 0)
  h.driver.unmount()
})
test('the Owner SePay setup writes the bank and secret, and reads only masked channel metadata', async () => {
  const driver = hookDriver(),
    requests = []
  class Data {
    get(name) {
      return {
        bankBin: '970436',
        accountNumber: '0000123456',
        accountName: 'FACILITY OWNER',
        webhookApiKey: 'secret-012345678901234567890123456789',
      }[name]
    }
  }
  const load = moduleLoader(
    { FormData: Data },
    {
      react: driver.react,
      'react/jsx-runtime': { jsx, jsxs: jsx },
      './facility-store': { useFacility: () => ({ facility: { id: 'facility' } }) },
      '@/lib/use-api': {
        useApi: (path) => {
          assert.equal(path, '/payments/facilities/facility/sepay-channel')
          return { data: null, reload() {} }
        },
      },
      '@/lib/api': {
        api: async (path, options) => {
          requests.push({ path, ...options })
          return {}
        },
      },
      '@/components/ui/input': { Input: 'input' },
      '@/components/ui/button': { Button: 'button' },
      '@/features/payments/bank-select': { BankSelect: 'banks' },
    },
  )
  const Page = load('features/facility/sepay-channel-page.tsx').SepayChannelPage,
    tree = driver.render(Page)
  assert.match(text(tree), /Kênh nhận tiền SePay/)
  const secret = nodes(tree).find((n) => n.props?.name === 'webhookApiKey')
  assert.equal(secret.props.type, 'password')
  assert.equal(secret.props.value, undefined)
  await nodes(tree)
    .find((n) => n.type === 'form')
    .props.onSubmit({ preventDefault() {}, currentTarget: { reset() {} } })
  assert.equal(requests.length, 1)
  const body = JSON.parse(requests[0].body)
  assert.equal(body.accountNumber, '0000123456')
  assert.equal(body.version, 0)
  assert.ok(body.webhookApiKey)
  assert.equal('clientId' in body, false)
  assert.equal('checksumKey' in body, false)
  driver.unmount()
})

for (const provider of ['SEPAY', 'DEMO']) {
  test(`REAL runtime renders ${provider === 'SEPAY' ? 'SePay bank instructions' : 'old DEMO history without a pay button'}`, () => {
    const driver = hookDriver()
    const payment = {
      id: 'payment',
      bookingId: 'booking',
      provider,
      purpose: 'BOOKING',
      amount: 120000,
      currency: 'VND',
      status: 'PENDING',
      expiresAt: '2026-11-01T08:00:00Z',
    }
    const load = moduleLoader(
      {},
      {
        react: driver.react,
        'react/jsx-runtime': { jsx, jsxs: jsx },
        'next/link': { default: 'a' },
        'next/navigation': { useRouter: () => ({ push() {} }) },
        '@/lib/auth-context': { useAuth: () => ({ user: { id: 'buyer', roles: ['CUSTOMER'] } }) },
        '@/features/auth/require-auth': { RequireAuth: 'auth' },
        '@/lib/use-api': {
          useApi: (url) => ({
            data:
              url === '/payments/payment'
                ? payment
                : url === '/payments/runtime'
                  ? { mode: 'REAL' }
                  : null,
            reload() {},
          }),
        },
        '@/lib/use-polling': { usePolling() {} },
        '@/components/ui/button': { Button: 'button' },
        '@/features/payments/bank-payment-instructions': {
          BankPaymentInstructions: 'bank-instructions',
        },
        './demo-finance': { notifyDemoMoneyChanged() {} },
        './countdown': { Countdown: 'countdown' },
        './payment-synchronization': { PaymentSynchronization: 'sync' },
      },
    )
    const wrapper = load('features/booking/payment-page.tsx').PaymentPage({ id: 'payment' })
    const tree = driver.render(wrapper.props.children.type, wrapper.props.children.props)
    assert.equal(
      nodes(tree).filter((n) => n.type === 'bank-instructions').length,
      provider === 'SEPAY' ? 1 : 0,
    )
    assert.equal(
      nodes(tree).some(
        (n) => n.type === 'button' && /Thanh toán mô phỏng|Giả lập thanh toán/.test(text(n)),
      ),
      false,
    )
    if (provider === 'DEMO') assert.match(text(tree), /GIAO DỊCH MÔ PHỎNG CŨ/)
    driver.unmount()
  })
}
