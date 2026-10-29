'use client'
import type { useFacilityReport } from './use-facility-report'
export function ReportPeer({ model }: { model: ReturnType<typeof useFacilityReport> }) {
  if (model.report?.mode !== 'REAL') return null
  const peer = model.report.peer
  return <section className="space-y-4 rounded-2xl border bg-white p-5">
    <h3 className="text-lg font-bold">Chuyển nhượng quyền sử dụng sân</h3>
    {!peer || peer.availability === 'UNAVAILABLE' ? <p role="alert">Chưa có dữ liệu chuyển nhượng; không coi là bằng 0.</p> : <>
      {peer.availability !== 'COMPLETE' && <p role="alert">Dữ liệu chuyển nhượng chưa đầy đủ.</p>}
      <dl className="grid gap-3 text-sm sm:grid-cols-2">
        {[
          ['Số lượt chuyển nhượng thành công trong kỳ', peer.completedUsageCount == null ? 'Chưa có dữ liệu' : String(peer.completedUsageCount)],
          ['Hồ sơ đang tranh chấp / chờ xác nhận quyền sân', String(peer.openUsageCases)],
        ].map(([label, value]) => <div key={label} className="rounded-lg bg-slate-50 p-3"><dt>{label}</dt><dd className="mt-1 font-semibold">{value}</dd></div>)}
      </dl>
      <p className="text-xs text-slate-500">Lượt thành công tính theo ngày bàn giao. Hồ sơ quyền sân còn mở gồm cả hồ sơ từ kỳ trước.</p>
    </>}
  </section>
}
