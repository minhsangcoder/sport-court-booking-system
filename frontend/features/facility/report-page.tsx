'use client'
import { Button } from '@/components/ui/button'
import {
  OwnerPageHead,
  Panel,
  amberNotice,
  card,
  outlinePill,
  redNotice,
} from '@/features/facility/owner-ui'
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
      <OwnerPageHead
        title="Báo cáo & Doanh thu"
        description="Khoản thu và hoạt động sân của cơ sở."
        actions={
          <Button
            variant="outline"
            className={outlinePill}
            onClick={resource.reload}
            disabled={resource.loading}
          >
            Làm mới báo cáo
          </Button>
        }
      />
      <ReportFilters model={model} />
      {resource.loading && (
        <div role="status" className={`${card} p-8 text-sm text-slate-500`}>
          Đang tổng hợp báo cáo…
        </div>
      )}
      {resource.error && (
        <div role="alert" className={redNotice}>
          <p>{resource.error}</p>
          <Button variant="outline" className="mt-3" onClick={resource.reload}>
            Thử lại
          </Button>
        </div>
      )}
      {report && stats && money && (
        <>
          {report.moneyAvailability !== 'COMPLETE' && (
            <p role="alert" className={amberNotice}>
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
          <Panel title="Khoản thu theo ngày">
            {available ? (
              <DailyChart days={report.daily} />
            ) : (
              <p className="text-sm text-slate-500">Biểu đồ chờ đủ dữ liệu ứng dụng.</p>
            )}
          </Panel>
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
