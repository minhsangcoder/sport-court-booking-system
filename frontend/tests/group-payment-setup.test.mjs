import assert from 'node:assert/strict'
import test from 'node:test'
import * as React from 'react'
import * as jsx from 'react/jsx-runtime'
import { renderToStaticMarkup } from 'react-dom/server'
import { moduleLoader, hookDriver, flush } from './support/load-module.mjs'

function nodes(tree) {
  if (!tree || typeof tree !== 'object') return []
  if (Array.isArray(tree)) return tree.flatMap(nodes)
  return [tree, ...nodes(tree.props?.children)]
}

test('group payment shows an inline invite, refreshes membership and only splits when the owner clicks', async () => {
  const driver = hookDriver(), paths = [], requests = [], copies = []
  let reloads = 0, finish
  const load = moduleLoader({ navigator: { clipboard: { writeText: async value => copies.push(value) } } }, {
    react: driver.react, 'react/jsx-runtime': jsx, 'next/link': { default: 'a' },
    '@/components/ui/button': { Button: 'button' },
    '@/lib/use-api': { useApi: path => { paths.push(path); return { data: path ? { code: 'SIGNED-INVITE' } : null } } },
    '@/lib/api': { api: (path, options) => { requests.push({ path, ...options }); return new Promise(resolve => { finish = resolve }) } },
  })
  const Setup = load('features/groups/payment-setup.tsx').GroupPaymentSetup
  let group = { id: 'g', allocationsLocked: false, members: [{ id: 'owner' }], maxMembers: 4 }
  const render = () => driver.render(() => Setup({ group, reload: () => reloads++ }))
  let tree = render()
  assert.equal(nodes(tree).find(n => n.type === 'textarea').props.value, 'SIGNED-INVITE')
  await nodes(tree).find(n => n.type === 'button' && n.props.children === 'Sao chép mã mời').props.onClick()
  assert.deepEqual(copies, ['SIGNED-INVITE'])
  group = { ...group, members: [...group.members, { id: 'new-member' }] }
  tree = render()
  assert.match(renderToStaticMarkup(tree), /Hiện có 2\/4 thành viên/)
  assert.equal(requests.length, 0)
  const button = nodes(tree).find(n => n.type === 'button' && n.props.children === 'Chia đều và bắt đầu thanh toán')
  const pending = button.props.onClick()
  button.props.onClick()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].path, '/groups/g/allocations')
  assert.equal(requests[0].method, 'PUT')
  assert.deepEqual(JSON.parse(requests[0].body), { mode: 'EQUAL', allocations: null })
  finish({}); await pending; await flush()
  assert.equal(reloads, 1)
  group = { ...group, allocationsLocked: true }
  assert.equal(nodes(render()).filter(n => n.type === 'textarea').length, 0)
  assert.equal(paths.at(-1), null)
  driver.unmount()
})

test('group page polls membership, removes the obsolete refund panel and hides invites after payment starts', () => {
  let polls = [], reloads = 0
  let group = {
    id: 'g', name: 'Nhóm thử', ownerId: 'owner', state: 'GROUP_PENDING', deadline: '2026-10-27T10:00:00Z',
    maxMembers: 4, allocationsLocked: false, totalPaid: 0,
    members: [{ id: 'm', userId: 'owner', amountPaid: 0, paymentRequestedAt: null }],
    booking: { id: 'g', facilityId: 'f', courtId: 'c', status: 'PENDING', amount: 120000,
      startsAt: '2026-10-27T11:00:00Z', endsAt: '2026-10-27T12:00:00Z',
      priceSnapshot: { moneyClass: 'DEMO', timezone: 'Asia/Ho_Chi_Minh', refundPolicy: { configured: true, rateBps: 9500 } } },
  }
  const load = moduleLoader({}, {
    react: React, 'react/jsx-runtime': jsx,
    'lucide-react': { Check: () => null, EyeOff: () => null, RotateCcw: () => null, Trash2: () => null },
    'next/link': { default: 'a' }, 'next/navigation': { useRouter: () => ({}) },
    '@/features/auth/require-auth': { RequireAuth: ({ children }) => children },
    '@/components/ui/button': { Button: 'button' },
    '@/lib/auth-context': { useAuth: () => ({ user: { id: 'owner' } }) },
    '@/lib/use-api': { useApi: path => ({ data: path === '/groups/g' ? group : path?.includes('/courts') ? [] : null, reload: () => reloads++ }) },
    '@/lib/use-polling': { usePolling: (callback, options) => polls.push({ callback, options }) },
    '@/features/booking/countdown': { Countdown: () => null },
    './hidden-groups': { useHiddenGroups: () => ({ isGroupHidden: () => false }) },
    './delete-group-dialog': { DeleteGroupDialog: () => null }, './group-extras': { GroupExtras: () => null },
    './payment-tracking': { GroupPaymentTracking: () => null },
    './payment-setup': { GroupPaymentSetup: () => 'INLINE-INVITE' },
  })
  const Page = load('features/groups/group-detail.tsx').GroupDetail
  const render = () => renderToStaticMarkup(jsx.jsx(Page, { id: 'g' }))
  assert.match(render(), /INLINE-INVITE/)
  assert.doesNotMatch(render(), /OBSOLETE-REFUND|Người mua chuyển nhượng|95%/)
  assert.equal(polls.at(-1).options.enabled, true)
  assert.equal(polls.at(-1).options.interval, 5000)
  polls.at(-1).callback(); assert.equal(reloads, 1)
  group = { ...group, allocationsLocked: true, members: [{ ...group.members[0], paymentRequestedAt: '2026-10-27T09:00:00Z' }] }
  assert.doesNotMatch(render(), /INLINE-INVITE|OBSOLETE-REFUND/)
})
