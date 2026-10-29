import assert from 'node:assert/strict'
import test from 'node:test'
import * as jsx from 'react/jsx-runtime'
import { renderToStaticMarkup } from 'react-dom/server'
import { moduleLoader } from './support/load-module.mjs'
const load = moduleLoader({}, { 'react/jsx-runtime': jsx, 'next/link': { default: 'a' }, '@/components/ui/button': { Button: 'button' } })
const bank = load('features/facility/report/report-obligations.tsx').ReportObligations
const peer = load('features/facility/report/report-peer.tsx').ReportPeer
const render = value => renderToStaticMarkup(value)
test('bank distinguishes missing money from zero and omits DEMO', () => {
 const model = { amount: String, money: {}, report: { mode: 'REAL' } }
 assert.match(render(bank({model})), /Chưa có dữ liệu/)
 model.money.bankCollected = '0'
 assert.match(render(bank({model})), />0</)
 assert.doesNotMatch(render(bank({model})), /người bán|mua lại|hoàn khách/)
 model.report.mode = 'DEMO'; assert.equal(bank({model}), null)
})
test('peer unavailable is not zero; empty page retains server usage total without money', () => {
 const model = { report: { mode: 'REAL' }, facility: { id: 'f' }, peerPage: 0, setPeerPage() {}, timestamp: String }
 assert.match(render(peer({model})), /Chưa có dữ liệu/)
 model.report.peer = { availability: 'PARTIAL', completedUsageCount: 12, openUsageCases: 7, transactions: { items: [], total: 0, totalPages: 0 } }
 const html = render(peer({model}))
 assert.match(html, /chưa đầy đủ/); assert.match(html, />7</); assert.match(html, />12</)
 assert.doesNotMatch(html, /Giá mua|Hồ sơ tiền|B tự khai|Tổng tiền/)
})

test('owner revenue ignores historical transfer fees', () => {
 const kpis = load('features/facility/report/report-kpis.tsx').ReportKpis
 const html = render(kpis({ model: { amount: String, money: { courtCollected: '120000', transferFees: '7000', operatingRevenue: '127000' }, stats: { bookingCount: 0, fullyPaidBookingCount: 0, cancelledCount: 0 } } }))
 assert.match(html, /120000/); assert.doesNotMatch(html, /127000|7000|phí chuyển nhượng|Phí chuyển nhượng/)
})
