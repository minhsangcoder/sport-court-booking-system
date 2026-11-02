'use client'
import type { useFacilityOverview } from '@/features/facility/use-facility-overview'
import { displayLabel, timezoneLabel } from '@/lib/display-labels'
import { CheckCircle2, ImageIcon, Layers, MapPin } from 'lucide-react'
import type { ReactNode } from 'react'
import { card } from './owner-ui'

function Stat({
  label,
  icon,
  value,
  note,
  delay,
}: {
  label: string
  icon: ReactNode
  value: ReactNode
  note: string
  delay: number
}) {
  return (
    <div className={`${card} home-rise p-5`} style={{ animationDelay: `${delay}ms` }}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-semibold uppercase tracking-wider text-emerald-700 dark:text-emerald-400">
          {label}
        </span>
        <span className="flex size-9 items-center justify-center rounded-xl bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300">
          {icon}
        </span>
      </div>
      <div className="mt-3 text-xl font-extrabold tracking-tight text-slate-950 dark:text-white">
        {value}
      </div>
      <p className="mt-1.5 text-xs text-slate-500 dark:text-slate-400">{note}</p>
    </div>
  )
}

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
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Stat
        label="Sân vận hành"
        icon={<Layers size={16} strokeWidth={1.75} />}
        value={courts.loading ? '…' : `${activeCourtsCount}/${totalCourtsCount} sân`}
        note={
          totalCourtsCount > 0 ? 'Đang bật trên tổng số sân của cơ sở' : 'Chưa có sân nào được thêm'
        }
        delay={0}
      />
      <Stat
        label="Trạng thái"
        icon={<CheckCircle2 size={16} strokeWidth={1.75} />}
        value={
          facility.status === 'ACTIVE'
            ? 'Đang hoạt động'
            : facility.status === 'PENDING_APPROVAL'
              ? 'Đang chờ duyệt'
              : facility.status === 'DRAFT'
                ? 'Bản nháp'
                : displayLabel(facility.status)
        }
        note={
          facility.status === 'ACTIVE'
            ? 'Đã hiển thị trên tìm kiếm công khai'
            : 'Hồ sơ cần được phê duyệt để mở bán'
        }
        delay={50}
      />
      <Stat
        label="Khu vực"
        icon={<MapPin size={16} strokeWidth={1.75} />}
        value={facility.province || 'Toàn quốc'}
        note={`Múi giờ vận hành: ${timezoneLabel(facility.timezone)}`}
        delay={100}
      />
      <Stat
        label="Hình ảnh"
        icon={<ImageIcon size={16} strokeWidth={1.75} />}
        value={images.loading ? '…' : `${images.data?.length ?? 0} ảnh`}
        note="Ảnh thực tế giúp khách dễ hình dung sân"
        delay={150}
      />
    </div>
  )
}
