'use client'
import type { useFacilityOverview } from '@/features/facility/use-facility-overview'
import { displayLabel, timezoneLabel } from '@/lib/display-labels'
import { CheckCircle2, ImageIcon, Layers, MapPin } from 'lucide-react'
export function FacilityStats({
  model,
}: {
  model: Pick<
    ReturnType<typeof useFacilityOverview>,
    'courts' | 'totalCourtsCount' | 'activeCourtsCount' | 'facility' | 'images'
  >
}) {
  const { courts, totalCourtsCount, activeCourtsCount, facility, images } = model
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <div className="rounded-xl border border-slate-200/90 bg-white p-4 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
        <div className="flex items-center justify-between">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            Sân vận hành
          </span>
          <div className="flex size-8 items-center justify-center rounded-lg border border-slate-200 bg-slate-50 text-emerald-700 dark:border-slate-700 dark:bg-slate-800">
            <Layers size={15} strokeWidth={1.75} />
          </div>
        </div>
        <div className="mt-3 flex items-baseline gap-2">
          <span className="text-2xl font-bold text-slate-900 dark:text-white">
            {courts.loading ? '…' : totalCourtsCount}
          </span>
          <span className="text-xs font-medium text-emerald-700 dark:text-emerald-400">
            ({activeCourtsCount} sân đang bật)
          </span>
        </div>
        <p className="mt-1.5 text-xs text-slate-500">
          {totalCourtsCount > 0
            ? 'Sẵn sàng phục vụ khách đặt trực tuyến'
            : 'Chưa có sân nào được thêm'}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200/90 bg-white p-4 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
        <div className="flex items-center justify-between">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            Trạng thái cơ sở
          </span>
          <div className="flex size-8 items-center justify-center rounded-lg border border-slate-200 bg-slate-50 text-emerald-700 dark:border-slate-700 dark:bg-slate-800">
            <CheckCircle2 size={15} strokeWidth={1.75} />
          </div>
        </div>
        <div className="mt-3">
          <span className="text-lg font-bold text-slate-900 dark:text-white">
            {facility.status === 'ACTIVE'
              ? 'Đang hoạt động'
              : facility.status === 'PENDING_APPROVAL'
                ? 'Đang chờ duyệt'
                : facility.status === 'DRAFT'
                  ? 'Bản nháp'
                  : displayLabel(facility.status)}
          </span>
        </div>
        <p className="mt-1.5 text-xs text-slate-500">
          {facility.status === 'ACTIVE'
            ? 'Đã hiển thị trên tìm kiếm công khai'
            : 'Hồ sơ cần được phê duyệt để mở bán'}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200/90 bg-white p-4 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
        <div className="flex items-center justify-between">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            Khu vực & Múi giờ
          </span>
          <div className="flex size-8 items-center justify-center rounded-lg border border-slate-200 bg-slate-50 text-emerald-700 dark:border-slate-700 dark:bg-slate-800">
            <MapPin size={15} strokeWidth={1.75} />
          </div>
        </div>
        <div className="mt-3">
          <span className="text-lg font-bold text-slate-900 dark:text-white truncate block">
            {facility.province || 'Toàn quốc'}
          </span>
        </div>
        <p className="mt-1.5 text-xs text-slate-500 truncate">
          Múi giờ vận hành: {timezoneLabel(facility.timezone)}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200/90 bg-white p-4 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
        <div className="flex items-center justify-between">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            Hình ảnh cơ sở
          </span>
          <div className="flex size-8 items-center justify-center rounded-lg border border-slate-200 bg-slate-50 text-emerald-700 dark:border-slate-700 dark:bg-slate-800">
            <ImageIcon size={15} strokeWidth={1.75} />
          </div>
        </div>
        <div className="mt-3 flex items-baseline gap-2">
          <span className="text-2xl font-bold text-slate-900 dark:text-white">
            {images.loading ? '…' : (images.data?.length ?? 0)}
          </span>
          <span className="text-xs text-slate-400">ảnh</span>
        </div>
        <p className="mt-1.5 text-xs text-slate-500">Ảnh thực tế giúp tăng 40% tỷ lệ đặt sân</p>
      </div>
    </div>
  )
}
