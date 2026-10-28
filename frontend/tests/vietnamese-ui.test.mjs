import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'
import test from 'node:test'
import ts from 'typescript'
import * as React from 'react'
import * as jsxRuntime from 'react/jsx-runtime'
import { renderToStaticMarkup } from 'react-dom/server'

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)))
const compiled = new Map()
function application({
  resources = {},
  fetch = async () => {
    throw Error('Unexpected request')
  },
} = {}) {
  const cache = new Map()
  const stubs = {
    react: React,
    'react/jsx-runtime': jsxRuntime,
    'next/link': { default: 'a' },
    'next/image': { default: 'img' },
    'next/navigation': { useRouter: () => ({ push() {} }) },
    '@/lib/auth-context': { useAuth: () => ({ user: { id: 'nhan-vien', roles: ['STAFF'] } }) },
    '@/lib/use-api': {
      useApi: (url) => ({ data: null, error: '', loading: false, reload() {}, ...resources[url] }),
    },
    '@/features/auth/require-auth': { RequireAuth: ({ children }) => children },
    '@/components/ui/button': { Button: 'button' },
    '@/components/ui/input': { Input: 'input' },
    './account-list': { AdminNav: () => null },
    'lucide-react': new Proxy({}, { get: () => () => null }),
  }
  function load(relative) {
    const file = path.resolve(root, relative)
    if (cache.has(file)) return cache.get(file)
    const exports = {}
    cache.set(file, exports)
    if (!compiled.has(file))
      compiled.set(
        file,
        ts.transpileModule(readFileSync(file, 'utf8'), {
          compilerOptions: {
            module: ts.ModuleKind.CommonJS,
            jsx: ts.JsxEmit.ReactJSX,
            target: ts.ScriptTarget.ES2022,
          },
        }).outputText,
      )
    vm.runInNewContext(
      compiled.get(file),
      {
        exports,
        fetch,
        Headers,
        FormData,
        Response,
        Request,
        URL,
        URLSearchParams,
        // A stale English preference must not switch part of this Vietnamese-only UI back to English.
        window: {},
        localStorage: { getItem: () => 'en' },
        require(name) {
          if (Object.hasOwn(stubs, name)) return stubs[name]
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
  return {
    load,
    render: (component, props = {}) => renderToStaticMarkup(React.createElement(component, props)),
  }
}

test('the screenshot refund-policy error is Vietnamese at the real API boundary, with wire metadata intact', async () => {
  const app = application({
    fetch: async () =>
      Response.json(
        {
          message:
            'The Owner must configure the facility refund policy before accepting new bookings',
          code: 'CONFLICT',
          details: { reason: 'REFUND_POLICY_NOT_CONFIGURED', facilityId: 'co-so' },
        },
        { status: 409 },
      ),
  })
  const client = app.load('lib/api.ts')
  await assert.rejects(client.api('/bookings/holds', { method: 'POST', body: '{}' }), (failure) => {
    assert.equal(
      failure.message,
      'Chủ cơ sở cần cấu hình chính sách hoàn tiền trước khi nhận lượt đặt mới.',
    )
    assert.equal(failure.status, 409)
    assert.equal(failure.code, 'CONFLICT')
    assert.equal(failure.details.reason, 'REFUND_POLICY_NOT_CONFIGURED')
    return true
  })
})

test('validation, permissions, dynamic limits and unknown server errors cannot leak English', async () => {
  const app = application({
    fetch: async () =>
      Response.json(
        {
          message: 'Unmapped English server failure',
          code: 'VALIDATION_ERROR',
          fieldErrors: [
            { field: 'guestName', message: 'must not be blank' },
            { field: 'guestPhone', message: 'size must be between 8 and 20' },
          ],
        },
        { status: 400 },
      ),
  })
  const { translateError } = app.load('lib/error-translator.ts')
  assert.equal(
    translateError('Missing facility permission: BOOKING_REFUND'),
    'Bạn chưa có quyền thực hiện thao tác này tại cơ sở: Xử lý khoản hoàn đã ghi nhận.',
  )
  assert.equal(
    translateError('Slot is not eligible: BOOKED'),
    'Khung giờ không đủ điều kiện đặt: Khung giờ đã có người đặt',
  )
  assert.match(
    translateError("Parameter 'bookingId' must be of type UUID"),
    /Mã lượt đặt không đúng định dạng/,
  )
  assert.match(translateError('Unmapped English server failure'), /^Không thể thực hiện yêu cầu/)
  assert.match(translateError('constructor'), /^Không thể thực hiện yêu cầu/)
  assert.equal(translateError('Lý do do khách nhập'), 'Lý do do khách nhập')
  assert.equal(translateError('My own note', { preserveUnknown: true }), 'My own note')
  await assert.rejects(
    app.load('lib/api.ts').api('/bookings/holds', { method: 'POST' }),
    (failure) => {
      assert.equal(failure.message, 'Thông tin không hợp lệ. Vui lòng kiểm tra lại.')
      assert.equal(failure.fieldErrors[0].field, 'guestName')
      assert.equal(failure.fieldErrors[0].message, 'Thông tin này không được để trống.')
      assert.equal(failure.fieldErrors[1].message, 'Độ dài phải từ 8 đến 20 ký tự.')
      return true
    },
  )
})

test('the real schedule displays the translated policy error and Vietnamese timezone while keeping slot state', () => {
  const app = application({
    resources: {
      '/facilities/co-so': { data: { name: 'Cơ sở thử nghiệm', timezone: 'Asia/Ho_Chi_Minh' } },
      '/facilities/co-so/courts': { data: [{ id: 'san', name: 'Sân 1' }] },
      '/bookings/availability?courtId=san&date=2026-10-23': {
        data: {
          slots: [
            {
              startsAt: '2026-10-23T06:00:00+07:00',
              endsAt: '2026-10-23T07:00:00+07:00',
              state: 'AVAILABLE',
              amount: 120000,
              currency: 'VND',
            },
          ],
        },
        error: 'Chủ cơ sở cần cấu hình chính sách hoàn tiền trước khi nhận lượt đặt mới.',
      },
    },
  })
  const html = app.render(app.load('features/discovery/availability-page.tsx').AvailabilityPage, {
    facilityId: 'co-so',
    courtId: 'san',
    initialDate: '2026-10-23',
  })
  assert.match(html, /Chọn khung giờ chưa giữ chỗ; giờ theo giờ Việt Nam \(UTC\+7\)/)
  assert.match(html, /role="alert"[^>]*>Chủ cơ sở cần cấu hình/)
  assert.match(html, /Còn trống/)
  assert.doesNotMatch(html, /The Owner|Asia\/Ho_Chi_Minh|\bslot\b/)
})

test('payment filters and records display Vietnamese while submitting original status values', () => {
  const app = application({
    resources: {
      '/admin/payments': {
        data: [
          {
            id: 'thanh-toan',
            bookingId: 'luot-dat',
            providerReference: 'REF-123',
            purpose: 'TRANSFER',
            status: 'SUCCESS',
            amount: 120000,
            currency: 'VND',
          },
        ],
      },
    },
  })
  const html = app.render(app.load('features/admin/payment-monitor.tsx').PaymentMonitor)
  assert.match(html, /value="PENDING">Chờ thanh toán/)
  assert.match(html, /value="SUCCESS">Thành công/)
  assert.match(html, /value="TRANSFER">Chuyển nhượng/)
  assert.match(html, /Chuyển nhượng · Thành công/)
  assert.match(html, /REF-123/)
  assert.doesNotMatch(html, />SUCCESS<|>TRANSFER</)
})

test('new direct booking explains full resale proceeds instead of advertising the buyer cancellation percentage', () => {
  const app = application({
    resources: {
      '/facilities/co-so/refund-policy': { data: { configured: true, rateBps: 9500 } },
    },
  })
  const html = app.render(app.load('features/discovery/availability-page.tsx').AvailabilityPage, {
    facilityId: 'co-so',
    courtId: 'san',
    initialDate: '2026-10-26',
  })
  assert.match(html, /không được hoàn tiền khi khách tự hủy/)
  assert.match(html, /100% giá chuyển nhượng đã chốt/)
  assert.match(html, /mất toàn bộ tiền đã trả/)
  assert.doesNotMatch(html, /95%|Tỷ lệ hoàn tiền khi hủy hợp lệ/)
})

test('history records translate keys, booleans and enums without rewriting customer names or identifiers', () => {
  const app = application()
  const html = app.render(app.load('components/record-details.tsx').RecordDetails, {
    value: {
      bookingId: 'BOOKING-123',
      status: 'CHECKED_IN',
      source: 'OFFLINE_COUNTER',
      roles: ['OWNER', 'STAFF'],
      active: true,
      guestName: 'Alice Brown',
      reason: 'Customer cancelled unpaid hold',
    },
  })
  assert.match(html, /Mã lượt đặt/)
  assert.match(html, /BOOKING-123/)
  assert.match(html, /Đang chơi/)
  assert.match(html, /Đặt tại quầy/)
  assert.match(html, /Chủ cơ sở/)
  assert.match(html, /Nhân viên/)
  assert.match(html, />Có</)
  assert.match(html, /Alice Brown/)
  assert.match(html, /Khách hủy lượt giữ chỗ chưa thanh toán/)
  assert.doesNotMatch(html, /CHECKED_IN|OFFLINE_COUNTER|>OWNER<|>STAFF<|>true</)
})

test('booking policy notice removes the entire obsolete resale refund block', () => {
  const app = application()
  const html = app.render(
    app.load('features/booking/refund-policy-notice.tsx').RefundPolicyNotice,
    {
      policy: { configured: true, rateBps: 9500 },
    },
  )
  assert.match(html, /Quy định hoàn tiền:/)
  assert.match(html, /Đăng chuyển nhượng lượt đặt/)
  assert.doesNotMatch(
    html,
    /95%|100%|<details|người mua lại|Chỉ được xác nhận vào sân|Khoản thu lỗi/,
  )
})

test('legacy cancellation errors no longer advertise refunds for resold bookings', () => {
  const { translateError } = application().load('lib/error-translator.ts')
  assert.match(
    translateError(
      'BLOCKED_RULE: facility paid cancellation requires the 100% refund execution/reconciliation protocol',
    ),
    /không hỗ trợ hủy trực tiếp/,
  )
})
