import assert from 'node:assert/strict'
import test from 'node:test'
import * as jsx from 'react/jsx-runtime'
import { renderToStaticMarkup } from 'react-dom/server'
import { moduleLoader, hookDriver, flush } from './support/load-module.mjs'

const plain = value => JSON.parse(JSON.stringify(value))
class Form extends FormData { constructor(values) { super(); for (const [name, value] of values ?? []) this.set(name, value) } }
const user = { id: 'customer', roles: ['CUSTOMER'], fullName: 'Khách thử', email: 'old@example.invalid', status: 'ACTIVE' }
const icons = new Proxy({}, { get: () => () => null })
const ui = { 'react/jsx-runtime': jsx, 'next/link': { default: 'a' }, 'next/image': { default: 'img' }, 'lucide-react': icons, '@/components/ui/button': { Button: 'button' }, '@/components/ui/input': { Input: 'input' } }
function elements(node, match) {
  if (!node || typeof node !== 'object') return []
  if (Array.isArray(node)) return node.flatMap(n => elements(n, match))
  return [...(match(node) ? [node] : []), ...elements(node.props?.children, match)]
}
const content = node => !node || typeof node === 'boolean' ? '' : typeof node !== 'object' ? String(node) : Array.isArray(node) ? node.map(content).join('') : content(node.props.children)

test('profile edits preserve PATCH fields, nullable avatar/date, session notification, copy and server validation', async () => {
  const driver = hookDriver(), calls = [], notifications = [], copied = []
  let reject = false, sessionUser = user
  class ApiError extends Error {}
  const load = moduleLoader({ navigator: { clipboard: { writeText: value => copied.push(value) } }, window: { dispatchEvent: event => notifications.push(event.detail) }, CustomEvent: class { constructor(_, { detail }) { this.detail = detail } } }, {
    ...ui, react: driver.react, '@/lib/auth-context': { useAuth: () => ({ user: sessionUser }) },
    '@/lib/api': { ApiError, api: async (path, options) => { calls.push({ path, ...options }); if (reject) throw new ApiError('Họ tên không hợp lệ.'); return { ...user, fullName: 'Tên mới' } } },
  })
  const hook = load('features/profile/use-profile.ts').useProfile
  driver.render(hook); let model = driver.render(hook)
  assert.equal(model.fullName, user.fullName)
  model.setFullName(' Tên mới '); model.setAvatarUrl(' '); model.setDateOfBirth('')
  model = driver.render(hook)
  // A session refresh from a photo upload keeps unsaved edits and takes the new photo.
  sessionUser = { ...user, avatarUrl: '/api/v1/users/avatars/new' }
  model = driver.render(hook); model = driver.render(hook)
  assert.equal(model.fullName, ' Tên mới ')
  assert.equal(model.avatarUrl, '/api/v1/users/avatars/new')
  model.setAvatarUrl(' ')
  model = driver.render(hook); await model.handleSave({ preventDefault() {} })
  assert.equal(calls[0].path, '/users/me'); assert.equal(calls[0].method, 'PATCH')
  assert.deepEqual(JSON.parse(calls[0].body), { fullName: 'Tên mới', avatarUrl: null, dateOfBirth: null })
  assert.equal(notifications.length, 1); assert.equal(notifications[0].fullName, 'Tên mới')
  driver.render(hook).copyUserId(); assert.deepEqual(copied, ['customer']); assert.equal(driver.render(hook).copiedId, true)
  reject = true; await driver.render(hook).handleSave({ preventDefault() {} })
  assert.equal(driver.render(hook).error, 'Họ tên không hợp lệ.'); assert.equal(notifications.length, 1)
  driver.unmount()
})

test('contact changes retain the old contact until OTP verification, reject unchanged values and resend the same channel', async () => {
  const driver = hookDriver(), calls = [], notices = []
  const challenge = { id: 'otp', channel: 'EMAIL', recipient: 'new@example.invalid', expiresAt: '2026-10-23T10:00:00Z', resendAfter: '2026-10-23T09:00:00Z', usable: true }
  const load = moduleLoader({ FormData: Form, setInterval: () => 1, clearInterval() {}, window: { dispatchEvent: event => notices.push(event.detail) }, CustomEvent: class { constructor(_, { detail }) { this.detail = detail } } }, {
    ...ui, react: driver.react, '@/lib/auth-context': { useAuth: () => ({ user, ready: true }) },
    '@/lib/use-api': { useApi: () => ({ data: [challenge], loading: false, error: '', reload: async () => calls.push({ path: 'reload' }) }) },
    '@/lib/api': { ApiError: Error, api: async (path, options) => { calls.push({ path, ...options }); return { ...user, email: challenge.recipient } }, authApi: { verify: async body => calls.push({ path: '/auth/verify', body }) } },
  })
  const hook = load('features/profile/use-contact.ts').useContact
  driver.render(hook).requestChange({ preventDefault() {}, currentTarget: [['recipient', user.email]] }, 'EMAIL')
  assert.match(driver.render(hook).error, /phải khác/); assert.equal(calls.length, 0)
  driver.render(hook).requestChange({ preventDefault() {}, currentTarget: [['recipient', ' new@example.invalid ']] }, 'EMAIL'); await flush()
  assert.equal(calls[0].path, '/users/me'); assert.deepEqual(JSON.parse(calls[0].body), { email: challenge.recipient })
  assert.equal(notices.length, 0); assert.equal(driver.render(hook).user.email, user.email)
  driver.render(hook).verify({ preventDefault() {}, currentTarget: [['code', '123456']] }, challenge); await flush()
  assert.deepEqual(plain(calls.find(c => c.path === '/auth/verify').body), { challengeId: 'otp', code: '123456' })
  assert.equal(notices[0].email, challenge.recipient)
  driver.render(hook).resend(challenge); await flush(); assert.ok(calls.some(c => c.path === '/users/me/contact-challenges/EMAIL/resend'))
  driver.unmount()
})

test('the actual permission editor submits independent refund/payout grants with the server version and retains stale/403 errors', async () => {
  const driver = hookDriver(), calls = [], reloads = []
  let failure = ''
  const load = moduleLoader({ fetch: async (path, options) => {
    calls.push({ path, ...options })
    return failure ? Response.json({ message: failure, code: 'CONFLICT' }, { status: 409 }) : Response.json({ data: {} })
  } }, {
    ...ui, react: driver.react,
    '@/lib/use-api': { useApi: () => ({ data: { version: 7 }, error: '', reload: () => reloads.push('version') }) },
  })
  const hook = load('features/staff/use-staff-profile.ts').useStaffProfile
  const props = { facility: 'facility-A', profile: { id: 'profile', bindingId: 'binding', permissions: ['BOOKING_READ', 'BOOKING_REFUND', 'TRANSFER_PAYOUT'], linked: true, active: true, version: 3 }, catalog: ['BOOKING_READ', 'BOOKING_REFUND', 'TRANSFER_PAYOUT'], reload: () => reloads.push('profiles') }
  driver.render(hook, props).setPermissions(['BOOKING_READ', 'TRANSFER_PAYOUT'])
  const view = load('features/staff/staff-permission-editor.tsx').StaffPermissionEditor
  const save = model => elements(view({ model }), n => n.type === 'button' && content(n) === 'Lưu quyền hạn')[0].props.onClick()
  await save(driver.render(hook, props))
  assert.equal(driver.render(hook, props).a.error, '')
  assert.equal(calls[0].path, '/api/v1/staff-bindings/facilities/facility-A/binding/permissions')
  assert.deepEqual(JSON.parse(calls[0].body), { permissions: ['BOOKING_READ', 'TRANSFER_PAYOUT'], expectedVersion: 7 })
  assert.deepEqual(reloads, ['version', 'profiles'])
  driver.render(hook, props).setPermissions(['BOOKING_READ', 'BOOKING_REFUND']); failure = 'Phiên bản đã thay đổi; vui lòng tải lại.'
  await save(driver.render(hook, props)); assert.equal(driver.render(hook, props).a.error, failure)
  assert.deepEqual(JSON.parse(calls[1].body).permissions, ['BOOKING_READ', 'BOOKING_REFUND'])
  failure = 'Bạn không có quyền thực hiện thao tác này.'; await save(driver.render(hook, props)); assert.equal(driver.render(hook, props).a.error, failure)
  driver.unmount()
})

test('facility gallery mutations keep multipart uploads, replacement paths and one shared query per resource', async () => {
  const driver = hookDriver(), calls = [], reads = [], reloads = [], form = new FormData()
  form.set('file', new File(['synthetic'], 'court.png', { type: 'image/png' }))
  form.reset = () => calls.push({ reset: true })
  class CopyForm extends FormData { constructor(source) { super(); for (const [key, value] of source) this.append(key, value) } }
  const load = moduleLoader({ FormData: CopyForm }, {
    ...ui, react: driver.react, '@/components/confirm-dialog': { confirmDanger: async () => true, confirmDialog: async () => true }, '@/features/facility/facility-store': { useFacility: () => ({ facility: { id: 'A' }, basePath: '/owner/facilities/A', editable: true }) },
    '@/lib/use-api': { useApi: path => { reads.push(path); return { data: [], reload: () => reloads.push(path) } } },
    '@/lib/api': { api: async (path, options) => calls.push({ path, ...options }) },
  })
  const hook = load('features/facility/use-facility-overview.ts').useFacilityOverview
  driver.render(hook).setReplacement('image-1'); let model = driver.render(hook)
  await model.upload({ preventDefault() {}, currentTarget: form }); await driver.render(hook).remove('image-2')
  assert.equal(calls[0].path, '/owner/facilities/A/images/image-1'); assert.equal(calls[0].method, 'PUT'); assert.ok(calls[0].body instanceof FormData)
  assert.equal(calls[2].path, '/owner/facilities/A/images/image-2'); assert.equal(calls[2].method, 'DELETE')
  assert.equal(new Set(reads).size, 2); assert.equal(reloads.length, 2)
  const view = load('features/facility/facility-gallery.tsx').FacilityGallery
  assert.match(renderToStaticMarkup(view({ model: driver.render(hook) })), /Ảnh|ảnh/)
  driver.unmount()
})
