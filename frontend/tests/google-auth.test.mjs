import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

const nextKey = 'sporthub-google-next'
const fallbackPath = '/customer/profile'
const sourceFiles = {
  state: new URL('../features/auth/google-auth-state.ts', import.meta.url),
  callback: new URL('../features/auth/google-auth-callback.tsx', import.meta.url),
  api: new URL('../src/lib/api.ts', import.meta.url),
}
const compiled = Object.fromEntries(Object.entries(sourceFiles).map(([name, path]) => [
  name,
  ts.transpileModule(readFileSync(path, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText,
]))

function storageWith(next) {
  const values = new Map(next == null ? [] : [[nextKey, next]])
  return {
    reads: 0,
    removals: 0,
    getItem(key) { this.reads++; return values.get(key) ?? null },
    setItem(key, value) { values.set(key, value) },
    removeItem(key) { this.removals++; values.delete(key) },
  }
}

function loadModule(name, globals, overrides = {}, cache = new Map()) {
  if (cache.has(name)) return cache.get(name)
  const exports = {}
  cache.set(name, exports)
  const dependencies = { './google-auth-state': 'state', '@/rebuilt/lib/api': 'api' }
  vm.runInNewContext(compiled[name], {
    exports,
    URL,
    Headers,
    FormData,
    CustomEvent: class { constructor(type, { detail }) { this.type = type; this.detail = detail } },
    ...globals,
    require(id) {
      if (id === 'next/link') return { default: 'a' }
      if (Object.hasOwn(overrides, id)) return overrides[id]
      if (dependencies[id]) return loadModule(dependencies[id], globals, overrides, cache)
      throw new Error(`Unexpected dependency: ${id}`)
    },
  }, { filename: sourceFiles[name].pathname })
  return exports
}

function callbackHarness(storage) {
  const effects = []
  const redirects = []
  const errors = []
  let refreshRequests = 0
  let respond
  const response = new Promise(resolve => { respond = resolve })
  const router = { replace: path => redirects.push(path) }
  const callback = loadModule('callback', {
    window: { sessionStorage: storage, dispatchEvent() {} },
    fetch() { refreshRequests++; return response },
  }, {
    react: {
      useEffect: effect => effects.push(effect),
      useState: () => [false, value => errors.push(value)],
    },
    'next/navigation': { useRouter: () => router },
    'next/link': { default() {} },
    'react/jsx-runtime': { jsx() {}, jsxs() {} },
  })
  callback.GoogleAuthCallback()
  return {
    redirects,
    errors,
    get refreshRequests() { return refreshRequests },
    setup: () => effects[0](),
    async finish(status = 200) {
      respond({
        status,
        ok: status === 200,
        async json() {
          return status === 200
            ? { data: { accessToken: 'test-access-token', user: { id: 'test-user' } } }
            : { message: 'Session expired' }
        },
      })
      await new Promise(resolve => setImmediate(resolve))
    },
  }
}

test('Google callback preserves the destination during Strict Mode effect replay and shares one refresh', async () => {
  const storage = storageWith('/bookings?tab=upcoming#today')
  const callback = callbackHarness(storage)
  callback.setup()()
  callback.setup()

  assert.equal(callback.refreshRequests, 1)
  await callback.finish()

  assert.deepEqual(callback.redirects, ['/bookings?tab=upcoming#today'])
  assert.deepEqual(callback.errors, [])
  assert.equal(storage.reads, 1)
  assert.equal(storage.removals, 1)
})

test('Google callback still completes when browser storage is blocked', async () => {
  const callback = callbackHarness({
    getItem() { throw new Error('SecurityError') },
    removeItem() { throw new Error('SecurityError') },
  })
  callback.setup()
  await callback.finish()

  assert.deepEqual(callback.redirects, [fallbackPath])
  assert.deepEqual(callback.errors, [])
})

test('Google callback rejects stored paths that normalize to protocol-relative URLs', async () => {
  const callback = callbackHarness(storageWith('/customer/..//evil.example/'))
  callback.setup()
  await callback.finish()

  assert.deepEqual(callback.redirects, [fallbackPath])
  assert.deepEqual(callback.errors, [])
})

test('Failed refresh reports an error only for the active callback effect', async () => {
  const callback = callbackHarness(storageWith('/bookings'))
  callback.setup()()
  callback.setup()
  await callback.finish(401)

  assert.equal(callback.refreshRequests, 1)
  assert.deepEqual(callback.redirects, [])
  assert.deepEqual(callback.errors, [true])
})

test('An unmounted callback does not consume a destination or report refresh failure', async () => {
  for (const status of [200, 401]) {
    const storage = storageWith('/bookings')
    const callback = callbackHarness(storage)
    callback.setup()()
    await callback.finish(status)

    assert.deepEqual(callback.redirects, [])
    assert.deepEqual(callback.errors, [])
    assert.equal(storage.reads, 0)
    assert.equal(storage.removals, 0)
  }
})

test('Post-login destinations reject paths that normalize to another origin', () => {
  const { postLoginPath } = loadModule('state', {})
  for (const candidate of [
    null,
    '',
    'https://evil.example/',
    '//evil.example/',
    '/\\evil.example/',
    '/\t/evil.example/',
    '/\n/evil.example/',
    '/\r/evil.example/',
    '/customer/..//evil.example/',
    '/%2e//evil.example/',
    '/customer/%2e%2e//evil.example/',
    '/customer/..///evil.example/',
    '/customer/..//evil.example/bookings?tab=upcoming#today',
    'javascript:alert(1)',
  ]) {
    assert.equal(postLoginPath(candidate), fallbackPath, JSON.stringify(candidate))
  }
  assert.equal(postLoginPath('/bookings?tab=upcoming#today'), '/bookings?tab=upcoming#today')
  assert.equal(postLoginPath('/customer/../bookings'), '/bookings')
})

test('Google destination storage roundtrips and consumes the destination once', () => {
  const storage = storageWith()
  const state = loadModule('state', { window: { sessionStorage: storage } })
  state.rememberGoogleNext('/bookings?tab=upcoming')
  assert.equal(state.consumeGoogleNext(), '/bookings?tab=upcoming')
  assert.equal(state.consumeGoogleNext(), fallbackPath)
  state.rememberGoogleNext('//evil.example/')
  assert.equal(state.consumeGoogleNext(), fallbackPath)
})

test('Google navigation helpers tolerate storage access and write failures', () => {
  const state = loadModule('state', { window: {
    get sessionStorage() { throw new Error('SecurityError') },
  } })
  assert.doesNotThrow(() => state.rememberGoogleNext('/bookings'))
  assert.equal(state.consumeGoogleNext(), fallbackPath)

  const writeBlocked = loadModule('state', { window: { sessionStorage: {
    setItem() { throw new Error('QuotaExceededError') },
  } } })
  assert.doesNotThrow(() => writeBlocked.rememberGoogleNext('/bookings'))
})
