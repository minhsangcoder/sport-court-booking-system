'use client'
import { FacilityGallery } from '@/features/facility/facility-gallery'
import { FacilityInformation } from '@/features/facility/facility-information'
import { FacilityStats } from '@/features/facility/facility-stats'
import { OverviewCourts } from '@/features/facility/overview-courts'
import { useFacilityOverview } from '@/features/facility/use-facility-overview'
import { ArrowRight, Clock, PlusCircle, Tag, Wrench } from 'lucide-react'
import Link from 'next/link'
import { card } from './owner-ui'

const actions = [
  {
    suffix: '/courts/new',
    label: 'Thêm sân mới',
    text: 'Khai báo sân để khách đặt',
    icon: PlusCircle,
  },
  { suffix: '/pricing', label: 'Cấu hình bảng giá', text: 'Giá theo giờ, ngày và sân', icon: Tag },
  { suffix: '/schedule', label: 'Lịch mở cửa', text: 'Giờ mở cửa và ngày nghỉ', icon: Clock },
  {
    suffix: '/maintenance',
    label: 'Khóa bảo trì sân',
    text: 'Tạm khóa sân khi sửa chữa',
    icon: Wrench,
  },
]

export function FacilityOverview() {
  const model = useFacilityOverview()
  const { basePath } = model
  return (
    <div className="space-y-6">
      <FacilityStats model={model} />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {actions.map(({ suffix, label, text, icon: Icon }, i) => (
          <div key={suffix} className="home-rise" style={{ animationDelay: `${(i + 4) * 50}ms` }}>
            <Link
              href={`${basePath}${suffix}`}
              className={`${card} group flex h-full items-center gap-3 p-4 transition hover:-translate-y-0.5 hover:border-emerald-500/50 hover:shadow-2xl`}
            >
              <span className="flex size-11 shrink-0 items-center justify-center rounded-2xl bg-emerald-700 text-white shadow-md">
                <Icon size={20} strokeWidth={1.6} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-bold text-slate-950 group-hover:text-emerald-700 dark:text-white dark:group-hover:text-emerald-400">
                  {label}
                </span>
                <span className="mt-0.5 block text-xs text-slate-500 dark:text-slate-400">
                  {text}
                </span>
              </span>
              <ArrowRight className="size-4 shrink-0 text-slate-400 transition group-hover:translate-x-0.5 group-hover:text-emerald-600" />
            </Link>
          </div>
        ))}
      </div>

      <OverviewCourts model={model} />
      <FacilityInformation model={model} />
      <FacilityGallery model={model} />
    </div>
  )
}
