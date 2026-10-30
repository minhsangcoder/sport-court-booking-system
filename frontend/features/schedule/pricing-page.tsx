'use client'
import { PricingForm } from '@/features/schedule/pricing-form'
import { PricingRules } from '@/features/schedule/pricing-rules'
import { money } from '@/features/schedule/types'
import { usePricing } from '@/features/schedule/use-pricing'
import { displayLabel } from '@/lib/display-labels'
import { Eye, Sparkles } from 'lucide-react'
import Link from 'next/link'
import { FlexiblePolicy } from './flexible-policy'

export function PricingPage() {
  const model = usePricing()
  const { basePath, error, rules, courts, categories, message, draft } = model

  return (
    <section className="space-y-6">
      <div className="rounded-2xl border border-slate-200/80 bg-white/90 p-6 shadow-xs backdrop-blur-sm dark:border-slate-800 dark:bg-slate-900/90">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="max-w-3xl">
            <p className="inline-flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-emerald-600 dark:text-emerald-400">
              <Sparkles size={13} />
              Quản lý lịch & Giá sân
            </p>
            <h2 className="mt-1 text-2xl font-extrabold tracking-tight text-slate-900 sm:text-3xl dark:text-white">
              Bảng giá
            </h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-500 dark:text-slate-400">
              Đặt giá theo giờ để khách chọn khoảng chơi từ 30 phút trở lên, theo bước 30 phút. Quy
              tắc theo sân hoặc ngày cụ thể được ưu tiên. Giá theo khung lịch cũ giữ nguyên đơn vị.
            </p>
          </div>
          <Link
            className="inline-flex items-center gap-1.5 rounded-full border border-emerald-600/30 bg-emerald-50 px-4 py-2 text-xs font-bold text-emerald-700 shadow-2xs transition-all hover:border-emerald-500 hover:bg-emerald-100 hover:shadow-xs active:scale-95 dark:border-emerald-500/30 dark:bg-emerald-950/40 dark:text-emerald-300"
            href={`${basePath}/schedule/preview`}
          >
            <Eye size={14} />
            Xem trước giá
          </Link>
        </div>
      </div>

      <FlexiblePolicy facilityId={model.facility.id} />

      <PricingForm key={model.editing?.id ?? 'new'} model={model} />

      {(error || rules.error || courts.error || categories.error) && (
        <p
          role="alert"
          className="rounded-2xl border border-red-200 bg-red-50 p-4 text-xs font-medium text-red-700 shadow-2xs dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
        >
          {error || rules.error || courts.error || categories.error}
        </p>
      )}

      {message && (
        <p
          role="status"
          className="rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-xs font-semibold text-emerald-800 shadow-2xs dark:border-emerald-900/50 dark:bg-emerald-950/40 dark:text-emerald-300"
        >
          {message}
        </p>
      )}

      {rules.loading && (
        <p role="status" className="text-xs font-medium text-slate-500">
          Đang tải bảng giá…
        </p>
      )}

      {rules.data?.length === 0 && (
        <div className="rounded-2xl border border-slate-200/80 bg-white p-8 text-center text-xs font-medium text-slate-500 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
          Chưa có quy tắc giá. khung giờ chưa có giá sẽ khóa thao tác đặt.
        </div>
      )}

      {draft && (
        <section className="rounded-2xl border border-dashed border-amber-300 bg-amber-50/60 p-6 shadow-sm dark:border-amber-800 dark:bg-amber-950/20">
          <div className="flex items-center gap-2">
            <span className="flex size-2 rounded-full bg-amber-500" />
            <h3 className="text-sm font-bold text-amber-900 dark:text-amber-200">
              Giá dự thảo, chưa lưu cấu hình
            </h3>
          </div>
          <div className="mt-3.5 divide-y divide-amber-200/60 rounded-xl border border-amber-200 bg-white/80 p-3 text-xs dark:border-amber-900/40 dark:bg-slate-900/60">
            {draft.slots.map((slot) => (
              <div
                key={slot.startsAt}
                className="flex items-center justify-between py-2 text-slate-700 dark:text-slate-300"
              >
                <span>
                  {new Date(slot.startsAt).toLocaleTimeString('vi-VN', {
                    timeZone: draft.timezone,
                  })}{' '}
                  · {displayLabel(slot.state)}
                </span>
                <span className="font-bold text-slate-900 dark:text-white">
                  {slot.amount == null ? 'Chưa có giá' : money(slot.amount)}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}

      <PricingRules model={model} />
    </section>
  )
}
