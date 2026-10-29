'use client'
import { Button } from '@/components/ui/button'
import { DailyChart } from '@/features/facility/report/daily-chart'
import { ReportCourts } from '@/features/facility/report/report-courts'
import { ReportFilters } from '@/features/facility/report/report-filters'
import { ReportKpis } from '@/features/facility/report/report-kpis'
import { ReportObligations } from '@/features/facility/report/report-obligations'
import { ReportTransactions } from '@/features/facility/report/report-transactions'
import { ReportPeer } from '@/features/facility/report/report-peer'
import { useFacilityReport } from '@/features/facility/report/use-facility-report'
export function FacilityReport() {
  const model = useFacilityReport()
  const { resource, report, stats, money, available, timestamp } = model
  return (
    <section className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold">Báo cáo &amp; Doanh thu</h2>
          <p className="mt-2 text-sm text-slate-500">
            Khoản thu và hoạt động sân của cơ sở.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Button variant="outline" onClick={resource.reload} disabled={resource.loading}>
            Làm mới báo cáo
          </Button>
        </div>
      </div>
      <ReportFilters model={model} />
      {resource.loading && (
        <div role="status" className="rounded-2xl border p-8 text-slate-500">
          Đang tổng hợp báo cáo…
        </div>
      )}
      {resource.error && (
        <div role="alert" className="rounded-2xl border border-red-200 bg-red-50 p-5 text-red-700">
          <p>{resource.error}</p>
          <Button variant="outline" className="mt-3" onClick={resource.reload}>
            Thử lại
          </Button>
        </div>
      )}
      {report && stats && money && (
        <>
          {report.moneyAvailability !== 'COMPLETE' && (
            <p role="alert" className="rounded-xl bg-amber-50 p-4 text-sm text-amber-900">
              {available
                ? 'Số tiền hiện là phần đã ghi nhận; còn khoản cần kiểm tra dữ liệu ứng dụng.'
                : 'Chưa tải đủ dữ liệu tiền. Hãy thử tải lại; đây không phải doanh thu bằng 0.'}
              <button className="ml-3 underline" onClick={resource.reload}>
                Thử lại
              </button>
            </p>
          )}
          <ReportKpis model={model} />
          <ReportObligations model={model} />
          <ReportPeer model={model} />
          <section className="rounded-2xl border bg-white p-5">
            <h3 className="mb-4 text-lg font-bold">Khoản thu theo ngày</h3>
            {available ? (
              <DailyChart days={report.daily} />
            ) : (
              <p className="text-sm text-slate-500">Biểu đồ chờ đủ dữ liệu ứng dụng.</p>
            )}
          </section>
          <ReportCourts model={model} />
          <ReportTransactions model={model} />
          <p className="text-xs text-slate-500">
            Cập nhật {timestamp(report.asOf)}. lượt đặt theo giờ bắt đầu; khoản thu/chi/hoàn theo
            thời điểm tương ứng. Xem báo cáo không thay đổi trạng thái lượt đặt hoặc thực hiện tiền.
          </p>
        </>
      )}
    </section>
  )
}
