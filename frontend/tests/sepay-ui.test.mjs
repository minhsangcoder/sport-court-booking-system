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
      'lucide-react': { CalendarClock: 'icon', Wallet: 'icon' },
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
      'lucide-react': new Proxy({}, { get: (_, name) => String(name) }),
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
  assert.ok(nodes(tree).some((n) => n.props?.title === 'Kênh nhận tiền SePay'))
  const secret = nodes(tree).find((n) => n.props?.name === 'webhookApiKey')
  assert.equal(secret.props.type, 'password')
  assert.equal(secret.props.value, undefined)
  assert.match(text(tree), /Cơ sở chưa có tài khoản nhận tiền/)
  assert.equal(nodes(tree).find((n) => n.type === 'banks').props.disabled, false)
  const submit = nodes(tree).find((n) => n.props?.type === 'submit')
  assert.equal(submit.props.disabled, false)
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

function sepayPage(channel, reload = () => {}) {
  const driver = hookDriver()
  const load = moduleLoader(
    {},
    {
      react: driver.react,
      'react/jsx-runtime': { jsx, jsxs: jsx },
      './facility-store': { useFacility: () => ({ facility: { id: 'facility' } }) },
      'lucide-react': new Proxy({}, { get: (_, name) => String(name) }),
      '@/lib/use-api': { useApi: () => ({ data: channel, reload }) },
      '@/lib/api': { api: async () => ({}) },
      '@/components/ui/input': { Input: 'input' },
      '@/components/ui/button': { Button: 'button' },
      '@/features/payments/bank-select': { BankSelect: 'banks', BankName: 'bank-name' },
    },
  )
  const Page = load('features/facility/sepay-channel-page.tsx').SepayChannelPage
  return { driver, render: () => driver.render(Page) }
}
const savedChannel = {
  channelId: 'channel',
  provider: 'SEPAY',
  version: 3,
  status: 'READY',
  bankBin: '970436',
  maskedAccountNumber: '***6410',
  accountName: 'FACILITY OWNER',
  webhookUrl: 'https://hook.example/webhook',
  lastWebhookAt: null,
}
const byText = (tree, pattern) => nodes(tree).find((n) => n.type === 'button' && pattern.test(text(n)))

test('a configured channel reads as saved details until the Owner chooses to change the account', () => {
  const page = sepayPage(savedChannel)
  let tree = page.render()
  assert.equal(nodes(tree).some((n) => n.type === 'form'), false)
  assert.match(text(tree), /\*\*\*6410/)
  assert.match(text(tree), /FACILITY OWNER/)
  byText(tree, /Đổi tài khoản/).props.onClick()
  tree = page.render()
  assert.ok(nodes(tree).some((n) => n.type === 'form'))
  const account = nodes(tree).find((n) => n.props?.name === 'accountNumber')
  assert.equal(account.props.defaultValue, undefined)
  byText(tree, /Hủy/).props.onClick()
  assert.equal(nodes(page.render()).some((n) => n.type === 'form'), false)
  page.driver.unmount()
})

test('saved is not reported as working until a webhook arrived with the current key', () => {
  const waiting = text(sepayPage(savedChannel).render())
  assert.match(waiting, /chưa nhận webhook/)
  assert.doesNotMatch(waiting, /Đã nhận webhook/)
  const received = text(
    sepayPage({ ...savedChannel, lastWebhookAt: '2026-10-31T10:00:00Z' }).render(),
  )
  assert.match(received, /Đã nhận webhook/)
  assert.doesNotMatch(received, /chưa nhận webhook/)
})

test('the webhook address appears only once the channel exists', () => {
  assert.match(text(sepayPage(null).render()), /sau khi lưu bước 2/)
  assert.match(text(sepayPage(savedChannel).render()), /https:\/\/hook\.example\/webhook/)
})

test('the generated webhook key satisfies the server rule and differs each time', () => {
  const page = sepayPage(null)
  let tree = page.render()
  const keyOf = (t) => nodes(t).find((n) => n.props?.name === 'webhookApiKey')
  assert.equal(keyOf(tree).props.defaultValue, undefined)
  byText(tree, /Tạo khóa tự động/).props.onClick()
  tree = page.render()
  const first = keyOf(tree).props.defaultValue
  assert.match(first, /^[A-Za-z0-9_.:-]{32,200}$/)
  assert.equal(keyOf(tree).props.type, 'password')
  byText(tree, /Tạo khóa tự động/).props.onClick()
  assert.notEqual(keyOf(page.render()).props.defaultValue, first)
  page.driver.unmount()
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

test('an absent facility channel is a valid empty response through the real API adapter', async () => {
  const load = moduleLoader({
    fetch: async (url) => {
      assert.equal(url, '/api/v1/payments/facilities/facility/sepay-channel')
      return new Response(null, { status: 204 })
    },
  })
  const result = await load('lib/api.ts').api('/payments/facilities/facility/sepay-channel')
  assert.equal(result, undefined)
})

for (const component of ['HeaderAccountMenu', 'HeaderMobileMenu']) {
  test(`${component} exposes Customer bank settings without showing them to Staff`, () => {
    const load = moduleLoader(
      {},
      {
        'react/jsx-runtime': { jsx, jsxs: jsx },
        'next/link': { default: 'a' },
        '@/components/user-avatar': { UserAvatar: 'user-avatar' },
        'lucide-react': new Proxy({}, { get: (_, name) => String(name) }),
      },
    )
    const file = component === 'HeaderAccountMenu' ? 'header-account-menu' : 'header-mobile-menu'
    const Menu = load(`components/layout/${file}.tsx`)[component]
    const model = {
      user: { id: 'customer', roles: ['CUSTOMER'], fullName: 'Customer' },
      isStaffOnly: false,
      busy: false,
      logout() {},
      setMobileMenuOpen() {},
      isActive() {
        return false
      },
    }
    const bankLinks = (tree) =>
      nodes(tree).filter((n) => n.type === 'a' && n.props.href === '/customer/profile/bank')
    const links = bankLinks(Menu({ model }))
    assert.equal(links.length, 1)
    assert.match(text(links[0]), /Ngân hàng nhận tiền chuyển nhượng/)
    assert.equal(
      bankLinks(
        Menu({ model: { ...model, user: { ...model.user, roles: ['STAFF'] }, isStaffOnly: true } }),
      ).length,
      0,
    )
  })
}

test('Customer bank settings show the photo above the read-only bank account', () => {
  const driver = hookDriver()
  const load = moduleLoader(
    {},
    {
      react: driver.react,
      'react/jsx-runtime': { jsx, jsxs: jsx },
      'next/link': { default: 'a' },
      '@/lib/auth-context': { useAuth: () => ({ user: { id: 'customer' } }) },
      '@/features/auth/require-auth': { RequireAuth: 'auth' },
      '@/features/payments/bank-destination-form': { BankDestinationForm: 'bank-form' },
      '@/features/profile/avatar-uploader': { AvatarUploader: 'avatar-uploader' },
    },
  )
  const wrapper = load('app/customer/profile/bank/page.tsx').default()
  const tree = driver.render(wrapper.props.children.type)
  assert.equal(nodes(tree).filter((n) => n.type === 'avatar-uploader').length, 1)
  assert.equal(nodes(tree).find((n) => n.type === 'bank-form').props.summary, true)
  driver.unmount()
})
