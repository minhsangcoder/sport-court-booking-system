'use client'
import type { useFacilityReport } from '@/features/facility/report/use-facility-report'
export function ReportObligations({ model }: {
  model: Pick<ReturnType<typeof useFacilityReport>, 'amount' | 'money' | 'report' | 'moneySource'>
}) {
  const { amount, money, report } = model
  if (!money || report?.mode !== 'REAL' || model.moneySource === 'CASH') return null
  return (
    <div className="rounded-2xl border bg-slate-50 p-5">
      <p className="text-sm text-slate-600">Ngân hàng đã nhận trong kỳ</p>
      <p className="mt-2 font-bold">{money.bankCollected == null ? 'Chưa có dữ liệu' : amount(money.bankCollected)}</p>
    </div>
  )
}
