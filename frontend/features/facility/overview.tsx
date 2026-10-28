'use client'
import { FacilityGallery } from '@/features/facility/facility-gallery'
import { FacilityInformation } from '@/features/facility/facility-information'
import { FacilityStats } from '@/features/facility/facility-stats'
import { OverviewCourts } from '@/features/facility/overview-courts'
import { useFacilityOverview } from '@/features/facility/use-facility-overview'
import { Clock, PlusCircle, Tag, Wrench } from 'lucide-react'
import Link from 'next/link'
export function FacilityOverview() {
  const model = useFacilityOverview()
  const { basePath } = model
  return (
    <div className="space-y-6">
      <FacilityStats model={model} />

      <div className="rounded-xl border border-slate-200/90 bg-white p-4 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
        <h3 className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 mb-3">
          Thao tác nhanh
        </h3>
        <div className="flex flex-wrap gap-2.5">
          <Link
            href={`${basePath}/courts/new`}
            className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-emerald-700 px-3.5 text-xs font-semibold text-white shadow-2xs hover:bg-emerald-800 transition-colors"
          >
            <PlusCircle size={14} strokeWidth={1.75} />
            <span>Thêm sân mới</span>
          </Link>
          <Link
            href={`${basePath}/pricing`}
            className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3.5 text-xs font-semibold text-slate-700 shadow-2xs hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <Tag size={14} strokeWidth={1.75} className="text-emerald-700" />
            <span>Cấu hình bảng giá</span>
          </Link>
          <Link
            href={`${basePath}/schedule`}
            className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3.5 text-xs font-semibold text-slate-700 shadow-2xs hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <Clock size={14} strokeWidth={1.75} className="text-slate-600" />
            <span>Lịch mở cửa</span>
          </Link>
          <Link
            href={`${basePath}/maintenance`}
            className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3.5 text-xs font-semibold text-slate-700 shadow-2xs hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <Wrench size={14} strokeWidth={1.75} className="text-slate-600" />
            <span>Khóa bảo trì sân</span>
          </Link>
        </div>
      </div>

      <OverviewCourts model={model} />
      <FacilityInformation model={model} />
      <FacilityGallery model={model} />
    </div>
  )
}
