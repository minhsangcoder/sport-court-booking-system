'use client'
import { Button } from '@/components/ui/button'
import { demoVnd, reportKindLabels } from '@/features/facility/demo-report'
import type { useFacilityReport } from '@/features/facility/report/use-facility-report'
import Link from 'next/link'
export function ReportTransactions({
  model,
}: {
  model: Pick<ReturnType<typeof useFacilityReport>, 'report' | 'timestamp' | 'page' | 'setPage'>
}) {
  const { report, timestamp, page, setPage } = model
  if (!report) return null
  return (
    <section className="rounded-2xl border bg-white p-5">
      <h3 className="text-lg font-bold">Giao dịch và ghi nhận trong kỳ</h3>
      <p className="mt-2 text-xs text-slate-500">
        Phí được tách từ tiền mua lại, không phải một khoản nộp thêm. Dòng thất bại không được tính
        như đã thực hiện.
      </p>
      <div className="mt-4 overflow-x-auto">
        <table className="w-full min-w-[760px] text-left text-sm">
          <thead className="bg-slate-50 text-slate-500">
            <tr>
              {['Thời điểm', 'Nghiệp vụ', 'Sân / nguồn đặt', 'Số tiền', 'Kết quả', 'Lượt đặt'].map(
                (label) => (
                  <th className="p-3 font-medium" key={label}>
                    {label}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody>
            {report.transactions.items.map((item) => (
              <tr key={item.id} className="border-t">
                <td className="p-3 whitespace-nowrap">{timestamp(item.effectiveAt)}</td>
                <td className="p-3">
                  {reportKindLabels[item.kind] || 'Giao dịch cơ sở'}
                  {!item.cashflow && (
                    <span className="mt-1 block text-xs text-slate-500">
                      {item.kind === 'FACILITY_TRANSFER_FEE'
                        ? 'Ghi nhận phí, không thu thêm'
                        : 'Ghi nhận áp dụng, không ghi thêm tiền ngân hàng'}
                    </span>
                  )}
                </td>
                <td className="p-3">
                  {item.courtName}
                  <span className="mt-1 block text-xs text-slate-500">
                    {item.source === 'OFFLINE_COUNTER' ? 'Tại quầy' : 'Trực tuyến'}
                  </span>
                </td>
                <td className="p-3 whitespace-nowrap font-medium">
                  {item.direction === 'OUT' ? '−' : ''}
                  {demoVnd(item.amount)}
                </td>
                <td className="p-3">
                  <span className={item.countsTowardTotals ? 'text-emerald-700' : 'text-red-700'}>
                    {report.mode === 'REAL'
                      ? item.confirmationMethod === 'CASH_RECEIVED'
                        ? 'Đã thu tiền mặt'
                        : item.confirmationMethod === 'MANUALLY_RECORDED'
                        ? 'Cơ sở xác nhận đã chuyển'
                        : item.confirmationMethod === 'HANDOFF_COMMITTED'
                          ? 'Bàn giao đã hoàn tất'
                          : 'Chứng từ ngân hàng đã xác thực'
                      : item.state === 'SUCCESS'
                        ? 'Thành công mô phỏng'
                        : 'Thất bại mô phỏng'}
                  </span>
                </td>
                <td className="p-3">
                  <Link
                    className="text-emerald-700 underline"
                    href={`/owner/bookings/${item.bookingId}`}
                  >
                    Xem lượt đặt
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {report.transactions.total === 0 && (
        <p className="py-6 text-center text-sm text-slate-500">
          Không có giao dịch phát sinh trong kỳ đã chọn.
        </p>
      )}
      <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm">
        <p>
          {report.transactions.total} dòng · Trang {report.transactions.page + 1}/
          {Math.max(1, report.transactions.totalPages)}
        </p>
        <div className="flex gap-2">
          <Button
            variant="outline"
            disabled={page === 0}
            onClick={() => setPage((value) => value - 1)}
          >
            Trang trước
          </Button>
          <Button
            variant="outline"
            disabled={page + 1 >= report.transactions.totalPages}
            onClick={() => setPage((value) => value + 1)}
          >
            Trang sau
          </Button>
        </div>
      </div>
    </section>
  )
}
