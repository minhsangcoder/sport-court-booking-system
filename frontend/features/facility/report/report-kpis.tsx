'use client'
import type { useFacilityReport } from '@/features/facility/report/use-facility-report'
export function ReportKpis({
  model,
}: {
  model: Pick<ReturnType<typeof useFacilityReport>, 'amount' | 'money' | 'stats' | 'mode'>
}) {
  const { amount, money, stats } = model
  if (!money || !stats) return null
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      {[
        ['Tiền sân đã thu', amount(money.courtCollected), 'Theo ngày thanh toán đã áp dụng'],
        ['Tổng khoản thu kinh doanh', amount(money.courtCollected), 'Tiền sân đã thu'],
        [
          'Lượt đặt bắt đầu trong kỳ',
          String(stats.bookingCount),
          `${stats.fullyPaidBookingCount} đã thanh toán đủ · ${stats.cancelledCount} đã hủy`,
        ],
      ].map(([label, value, note]) => (
        <article
          key={label}
          className="rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 p-5"
        >
          <h3 className="text-sm text-slate-600">{label}</h3>
          <p className="mt-3 break-words text-2xl font-extrabold tracking-tight text-emerald-700 dark:text-emerald-400">
            {value}
          </p>
          <p className="mt-2 text-xs text-slate-500">{note}</p>
        </article>
      ))}
    </div>
  )
}
