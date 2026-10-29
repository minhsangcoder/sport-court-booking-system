import assert from 'node:assert/strict'
import test from 'node:test'
import * as jsx from 'react/jsx-runtime'
import { moduleLoader, hookDriver, deferred, flush } from './support/load-module.mjs'

function selection(from, to) {
  return [{ startsAt: `2026-10-26T${from}:00Z`, endsAt: `2026-10-26T${to}:00Z`, amount: 51 }]
}

test('the current availability page sorts reverse clicks, blocks occupied cells and requires a server quote', () => {
  const driver = hookDriver()
  let selected = []
  const cells = [
    ...selection('14:30', '15:00'),
    ...selection('15:00', '15:30'),
    ...['BOOKED', 'HELD', 'BLOCKED', 'MAINTENANCE'].map((state, i) => ({
      ...selection(`1${i + 6}:00`, `1${i + 6}:30`)[0],
      state,
    })),
  ].map((cell) => ({ state: 'AVAILABLE', currency: 'VND', ...cell }))
  const pure = moduleLoader(
    {},
    { react: driver.react },
  )('features/discovery/use-interval-quote.ts').selectionInterval
  const load = moduleLoader(
    {},
    {
      react: driver.react,
      'react/jsx-runtime': jsx,
      'next/link': { default: 'a' },
      'next/navigation': { useRouter: () => ({ push() {} }) },
      'lucide-react': { Navigation: () => null },
      '@/components/ui/button': { Button: 'button' },
      '@/components/ui/input': { Input: 'input' },
      '@/lib/api': {
        api() {
          throw new Error('No hold may be sent without a quote')
        },
        ApiError: Error,
      },
      '@/lib/auth-context': { useAuth: () => ({ user: { id: 'customer' } }) },
      '@/lib/use-polling': { usePolling() {} },
      '@/lib/use-api': {
        useApi: (url) => ({
          data: url?.startsWith('/bookings/availability')
            ? { slots: cells }
            : url?.endsWith('/courts')
              ? [{ id: 'court', name: 'Sân 1' }]
              : url === '/facilities/facility'
                ? { name: 'Cơ sở', timezone: 'Asia/Ho_Chi_Minh' }
                : null,
          reload() {},
          loading: false,
        }),
      },
      '@/features/booking/admission-notice': {
        AdmissionNotice: () => null,
        AdmissionErrorContext: () => null,
      },
      './use-interval-quote': {
        useIntervalQuote: (court, value) => {
          selected = value
          return { ...pure(court, value), quote: null, loading: false }
        },
      },
    },
  )
  const Page = load('features/discovery/availability-page.tsx').AvailabilityPage
  const render = () =>
    driver.render(() =>
      Page({ facilityId: 'facility', courtId: 'court', initialDate: '2026-10-26' }),
    )
  function nodes(tree) {
    if (!tree || typeof tree !== 'object') return []
    if (Array.isArray(tree)) return tree.flatMap(nodes)
    return [tree, ...nodes(tree.props?.children)]
  }
  let tree = render()
  for (const cell of cells)
    assert.equal(
      nodes(tree).find((n) => n.type === 'button' && n.key === cell.startsAt).props.disabled,
      cell.state !== 'AVAILABLE',
    )
  nodes(tree)
    .find((n) => n.type === 'button' && n.key === cells[1].startsAt)
    .props.onClick()
  tree = render()
  nodes(tree)
    .find((n) => n.type === 'button' && n.key === cells[0].startsAt)
    .props.onClick()
  tree = render()
  assert.deepEqual(
    Array.from(selected, (cell) => cell.startsAt),
    [cells[0].startsAt, cells[1].startsAt],
  )
  assert.equal(pure('court', selected).interval.endsAt, cells[1].endsAt)
  const startSelect = nodes(tree).filter((n) => n.type === 'select')[0]
  startSelect.props.onChange({ target: { value: cells[0].startsAt } })
  tree = render()
  assert.equal(selected[0].startsAt, '2026-10-26T14:30:00Z')
  assert.equal(selected.at(-1).endsAt, '2026-10-26T15:30:00Z')
  assert.equal(
    nodes(tree).find((n) => n.props?.children === 'Xem tóm tắt và giữ chỗ').props.disabled,
    true,
  )
  cells[0].state = 'BLOCKED'
  render()
  assert.equal(selected.length, 0, 'a polling update must invalidate an occupied selection')
  driver.unmount()
})

test('free ranges merge only adjacent available cells and reject selections through a hold or break', () => {
  const { availableRanges, rangeSelection } = moduleLoader()(
    'features/discovery/available-ranges.ts',
  )
  const cells = [
    ...selection('14:00', '14:30'),
    ...selection('14:30', '15:00'),
    { ...selection('15:00', '15:30')[0], state: 'HELD' },
    ...selection('15:30', '16:00'),
    ...selection('17:00', '17:30'),
  ].map((s) => ({ state: 'AVAILABLE', ...s }))
  assert.deepEqual(JSON.parse(JSON.stringify(availableRanges(cells))), [
    { startsAt: cells[0].startsAt, endsAt: cells[1].endsAt },
    { startsAt: cells[3].startsAt, endsAt: cells[3].endsAt },
    { startsAt: cells[4].startsAt, endsAt: cells[4].endsAt },
  ])
  assert.equal(rangeSelection(cells, cells[0].startsAt, cells[1].endsAt).length, 2)
  assert.equal(rangeSelection(cells, cells[0].startsAt, cells[3].endsAt).length, 0)
  assert.equal(rangeSelection(cells, cells[3].startsAt, cells[4].endsAt).length, 0)
})

test('selection uses one continuous interval of at least 30 minutes without an upper limit', () => {
  const { selectionInterval } = moduleLoader(
    {},
    { react: hookDriver().react },
  )('features/discovery/use-interval-quote.ts')
  assert.equal(
    selectionInterval('court', selection('15:30', '16:00')).interval.endsAt,
    '2026-10-26T16:00:00Z',
  )
  assert.equal(
    selectionInterval('court', selection('15:30', '20:00')).interval.endsAt,
    '2026-10-26T20:00:00Z',
  )
  const slots = [...selection('15:30', '16:00'), ...selection('16:00', '16:30')]
  assert.equal(selectionInterval('court', slots).interval.endsAt, '2026-10-26T16:30:00Z')
  assert.ok(
    selectionInterval('court', [...selection('15:30', '16:00'), ...selection('16:30', '17:00')])
      .error,
  )
})

test('debounced quotes ignore older replies, abort on selection/unmount and display the server total', async () => {
  const driver = hookDriver(),
    timers = new Map(),
    calls = []
  let timerId = 0
  const load = moduleLoader(
    {
      setTimeout(fn) {
        const id = ++timerId
        timers.set(id, fn)
        return id
      },
      clearTimeout(id) {
        timers.delete(id)
      },
      fetch(url, options) {
        const reply = deferred()
        calls.push({ url, options, reply })
        return reply.promise
      },
    },
    { react: driver.react },
  )
  const { useIntervalQuote } = load('features/discovery/use-interval-quote.ts')
  const render = (slots) => driver.render((value) => useIntervalQuote('court', value), slots)
  const run = () => {
    for (const [id, fn] of timers) {
      timers.delete(id)
      fn()
    }
  }
  const first = selection('15:00', '16:00'),
    second = selection('15:30', '16:30')
  assert.equal(render(first).loading, true)
  render(second)
  run()
  await flush()
  assert.equal(calls.length, 1)
  assert.equal(JSON.parse(calls[0].options.body).startsAt, second[0].startsAt)
  render(first)
  assert.equal(calls[0].options.signal.aborted, true)
  run()
  await flush()
  calls[1].reply.resolve(Response.json({ data: { amount: 101 } }))
  await flush()
  assert.equal(render(first).quote.amount, 101)
  calls[0].reply.resolve(Response.json({ data: { amount: 102 } }))
  await flush()
  assert.equal(render(first).quote.amount, 101)
  render(second)
  run()
  await flush()
  driver.unmount()
  assert.equal(calls[2].options.signal.aborted, true)
  calls[2].reply.resolve(Response.json({ data: { amount: 200 } }))
  await flush()
})
