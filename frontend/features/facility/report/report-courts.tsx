'use client'
import { hours } from '@/features/facility/report/report-format'
import type { useFacilityReport } from '@/features/facility/report/use-facility-report'
export function ReportCourts({
  model,
}: {
  model: Pick<ReturnType<typeof useFacilityReport>, 'stats' | 'report' | 'amount'>
}) {
  const { stats, report, amount } = model
  if (!stats || !report) return null
  return (
    <section className="rounded-2xl border bg-white p-5">
      <div className="mb-4 flex flex-wrap justify-between gap-2">
        <h3 className="text-lg font-bold">Hiệu suất theo sân</h3>
        <p className="text-sm text-slate-500">
          {hours(stats.bookedMinutes)} giờ đặt · {hours(stats.usedMinutes)} giờ sử dụng ghi nhận
        </p>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[750px] text-left text-sm">
          <caption className="sr-only">Mỗi sân một dòng trong kỳ báo cáo đã chọn</caption>
          <thead className="bg-slate-50 text-slate-500">
            <tr>
              {[
                'Sân',
                'Lượt đặt',
                'Đủ tiền',
                'Giờ đặt',
                'Giờ sử dụng',
                'Tiền sân',
                'Khoản thu',
              ].map((label) => (
                <th key={label} className="p-3 font-medium">
                  {label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {report.courts.map((court) => (
              <tr key={court.courtId} className="border-t">
                <th scope="row" className="p-3 font-semibold">
                  {court.courtName}
                </th>
                <td className="p-3">{court.bookingCount}</td>
                <td className="p-3">{court.fullyPaidBookingCount}</td>
                <td className="p-3">{hours(court.bookedMinutes)}</td>
                <td className="p-3">{hours(court.usedMinutes)}</td>
                <td className="p-3 whitespace-nowrap">{amount(court.money.courtCollected)}</td>
                <td className="p-3 whitespace-nowrap">{amount(court.money.courtCollected)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {report.courts.length === 0 && (
        <p className="py-5 text-sm text-slate-500">Không có phát sinh theo sân trong kỳ này.</p>
      )}
      <p className="mt-4 text-xs text-slate-500">
        Giờ sử dụng chỉ tính sau xác nhận vào sân, giới hạn trong lịch đã đặt. Chưa có đủ lịch sử
        giờ mở cửa để tính tỷ lệ lấp đầy.
      </p>
    </section>
  )
}
