import test from 'node:test'
import assert from 'node:assert/strict'
import { moduleLoader, deferred, flush, hookDriver } from './support/load-module.mjs'

test('chat mutations refresh their own resource without reloading calendars, reports or bank permissions', async () => {
  const load = moduleLoader({ fetch: async () => Response.json({ data: {} }) })
  const { api } = load('lib/api.ts'),
    reads = load('lib/read-state.ts')
  const controls = [
    '/bookings/availability?court=c',
    '/owner/facilities/f/reports',
    '/payments/bank-profile',
    '/payments/banks',
  ]
  const before = controls.map(reads.readVersion)
  const chat = '/transfers/conversations/c/messages?after=2',
    first = reads.readVersion(chat)
  await api('/transfers/conversations/c/messages', { method: 'POST', body: '{"text":"hello"}' })
  assert.notEqual(reads.readVersion(chat), first)
  assert.deepEqual(controls.map(reads.readVersion), before)
  await api('/transfers/a/quote', { method: 'POST', body: '{}' })
  assert.deepEqual(controls.map(reads.readVersion), before)
  await api('/transfers/acquisitions/a/confirm-receipt', { method: 'POST', body: '{}' })
  assert.notEqual(reads.readVersion('/payments/bank-profile'), before[2])
  assert.notEqual(reads.readVersion('/bookings/availability?court=c'), before[0])
  const catalog = reads.readVersion('/payments/banks')
  await api('/unknown-money-command', { method: 'POST', body: '{}' })
  assert.notEqual(reads.readVersion('/payments/banks'), catalog)
})

test('private images and private command replies from a previous account cannot reach the next account', async () => {
  const image = deferred(),
    mutation = deferred()
  const load = moduleLoader({
    fetch: async (url) =>
      url.endsWith('/auth/login')
        ? Response.json({ data: { accessToken: 'B', user: { id: 'B', roles: ['CUSTOMER'] } } })
        : url.endsWith('/qr')
          ? image.promise
          : mutation.promise,
  })
  const client = load('lib/api.ts')
  const pendingImage = client.privateImage(
    '/payments/peer-acquisitions/a/qr',
    new AbortController().signal,
  )
  const pendingCommand = client.api('/transfers/acquisitions/a/confirm-receipt', {
    method: 'POST',
    body: '{}',
  })
  await client.authApi.login('B', 'password')
  image.resolve(new Response(new Uint8Array([1, 2]), { headers: { 'Content-Type': 'image/png' } }))
  mutation.resolve(Response.json({ data: { holder: 'old-account' } }))
  await assert.rejects(pendingImage, { name: 'AbortError' })
  await assert.rejects(pendingCommand, { name: 'AbortError' })
})

test('peer mutation rejects double click and preserves its key through an uncertain reply', async () => {
  const calls = [],
    driver = hookDriver()
  const load = moduleLoader(
    {
      fetch: (url, options) => {
        const reply = deferred()
        calls.push({ url, options, reply })
        return reply.promise
      },
    },
    { react: driver.react },
  )
  const hook = load('features/transfers/use-peer-command.ts').usePeerCommand
  const command = driver.render(hook),
    path = '/transfers/acquisitions/a/confirm-receipt',
    body = { version: 1, termsHash: 'a'.repeat(64) }
  const first = command.run(path, body)
  await command.run(path, body)
  assert.equal(calls.length, 1)
  calls[0].reply.resolve(
    Response.json({ message: 'Chưa rõ kết quả', code: 'UNCERTAIN' }, { status: 503 }),
  )
  await first
  const retry = driver.render(hook).run(path, body)
  calls[1].reply.resolve(Response.json({ data: { phase: 'COMPLETED' } }))
  assert.equal((await retry).phase, 'COMPLETED')
  assert.equal(
    calls[0].options.headers.get('Idempotency-Key'),
    calls[1].options.headers.get('Idempotency-Key'),
  )
  const original = driver.render(hook).run(path, { ...body, version: 2 })
  calls[2].reply.resolve(Response.json({ message: 'Hết phiên' }, { status: 401 }))
  await flush()
  calls[3].reply.resolve(
    Response.json({ data: { accessToken: 'new', user: { id: 'A', roles: ['CUSTOMER'] } } }),
  )
  await original
  const count = calls.length
  await driver.render(hook).run(path, { ...body, version: 2 })
  assert.equal(calls.length, count)
  assert.equal(driver.render(hook).blocked, true)
  driver.unmount()
})

test('bank forms reset per account and guest destinations never use a personal bank override', async () => {
  const driver = hookDriver(),
    paths = [],
    writes = [],
    changes = []
  let user = { id: 'A' }
  const jsx = (type, props, key) => ({ type, props, key })
  class Data {
    constructor(form) {
      this.fields = form.fields
    }
    get(name) {
      return this.fields[name]
    }
  }
  const load = moduleLoader(
    { FormData: Data },
    {
      react: driver.react,
      'react/jsx-runtime': { jsx, jsxs: jsx },
      '@/lib/auth-context': { useAuth: () => ({ user }) },
      '@/lib/use-api': {
        useApi: (path) => {
          paths.push(path)
          return {
            data: null,
            loading: Boolean(path),
            error: path ? 'Chưa kiểm tra được nghĩa vụ ngân hàng.' : '',
            reload: () => {},
          }
        },
      },
      '@/lib/api': {
        api: async (path, options) => {
          writes.push({ path, body: JSON.parse(options.body) })
          return { id: 'guest-revision' }
        },
      },
      '@/components/ui/input': { Input: 'input' },
      '@/components/ui/button': { Button: 'button' },
      './bank-select': { BankSelect: 'bank-select' },
      './bank-profile-status': { BankProfileStatus: 'bank-profile' },
    },
  )
  const Form = load('features/payments/bank-destination-form.tsx').BankDestinationForm
  const props = { value: '', onChange: (value) => changes.push(value) }
  const a = Form(props)
  user = { id: 'B' }
  const b = Form(props)
  assert.notEqual(a.key, b.key)
  function nodes(tree) {
    return !tree
      ? []
      : Array.isArray(tree)
        ? tree.flatMap(nodes)
        : typeof tree === 'object'
          ? [tree, ...nodes(tree.props?.children)]
          : []
  }
  const personal = driver.render(b.type, b.props)
  const blocked = nodes(personal).find((node) => node.type === 'form')
  await blocked.props.onSubmit({ preventDefault() {}, currentTarget: {} })
  assert.equal(writes.length, 0)
  driver.unmount()
  const guestDriver = hookDriver()
  // Fresh hooks represent the separate guest form lifetime.
  Object.assign(driver.react, guestDriver.react)
  const guest = Form({ ...props, guestBookingId: 'guest-booking' })
  const tree = guestDriver.render(guest.type, guest.props)
  assert.equal(paths.at(-1), null)
  const form = nodes(tree).find((node) => node.type === 'form')
  await form.props.onSubmit({
    preventDefault() {},
    currentTarget: {
      fields: { bankBin: '970436', accountNumber: '000123456789', accountName: 'KHACH THU' },
      reset() {},
    },
  })
  assert.equal(writes[0].path, '/payments/bookings/guest-booking/guest-bank-destinations')
  assert.equal(Object.hasOwn(writes[0].body, 'expectedProfileVersion'), false)
  assert.deepEqual(changes, ['guest-revision'])
  guestDriver.unmount()
})
