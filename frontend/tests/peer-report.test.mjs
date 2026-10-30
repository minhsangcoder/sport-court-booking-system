import test from 'node:test'
import assert from 'node:assert/strict'
import { moduleLoader } from './support/load-module.mjs'
const jsx = (type, props) => ({ type, props })
const load = moduleLoader(
  {},
  {
    'react/jsx-runtime': { jsx, jsxs: jsx },
    'next/link': { default: 'a' },
    '@/components/ui/button': { Button: 'button' },
  },
)
const Report = load('features/facility/report/report-peer.tsx').ReportPeer
const nodes = (tree) =>
  !tree
    ? []
    : Array.isArray(tree)
      ? tree.flatMap(nodes)
      : typeof tree === 'object'
        ? [tree, ...nodes(tree.props?.children)]
        : []
const text = (tree) =>
  !tree
    ? ''
    : Array.isArray(tree)
      ? tree.map(text).join(' ')
      : typeof tree === 'object'
        ? text(tree.props?.children)
        : String(tree)
test('peer report labels declarations and usage decisions separately and paginates without altering cash rows', () => {
  const pages = []
  const model = {
    report: {
      mode: 'REAL',
      courts: [{ courtId: 'court', courtName: 'Sân thử' }],
      peer: {
        availability: 'COMPLETE',
        openUsageCases: 1,
        openMoneyCases: 1,
        buyerDeclaredAmount: '100000',
        sellerAcknowledgedAmount: '0',
        ownerAwardedAmount: '0',
        returnDeclaredAmount: '40000',
        returnAcknowledgedAmount: '0',
        transactions: {
          page: 0,
          size: 1,
          total: 2,
          totalPages: 2,
          items: [
            {
              acquisitionId: 'one',
              bookingId: 'booking',
              courtId: 'court',
              amount: '100000',
              phase: 'DISPUTED',
              confirmationMethod: 'BUYER_DECLARED',
              moneyState: 'OPEN',
              createdAt: '2026-10-25T00:00:00Z',
            },
          ],
        },
      },
    },
    peerPage: 0,
    setPeerPage: (action) => pages.push(action(0)),
    timestamp: () => '25/10/2026',
    facility: { id: 'facility' },
  }
  const tree = Report({ model }),
    rendered = text(tree)
  assert.match(rendered, /Số lượt chuyển nhượng thành công trong kỳ/)
  assert.match(rendered, /Phiên đang chờ người bán xác nhận/)
  assert.equal(Report({ model: { ...model, report: { mode: 'DEMO' } } }), null)
  assert.match(
    text(Report({ model: { ...model, report: { mode: 'REAL', peer: null } } })),
    /không coi là bằng 0/,
  )
})
