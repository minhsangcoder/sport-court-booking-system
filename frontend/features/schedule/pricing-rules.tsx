'use client'
import { card, dangerPill, outlinePill } from '@/features/facility/owner-ui'
import { PricingHistory } from '@/features/schedule/pricing-history'
import { money, weekdays } from '@/features/schedule/types'
import type { usePricing } from '@/features/schedule/use-pricing'
import { Building2, Calendar, Clock, Layers, PencilLine, PowerOff } from 'lucide-react'

export function PricingRules({
  model,
}: {
  model: Pick<
    ReturnType<typeof usePricing>,
    'rules' | 'courts' | 'facility' | 'busy' | 'setEditing' | 'setDateMode' | 'setDraft' | 'disable'
  >
}) {
  const { rules, courts, facility, busy, setEditing, setDateMode, setDraft, disable } = model

  return (
    <div className="space-y-4">
      <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
        Quy tắc giá ({rules.data?.length ?? 0})
      </h3>
      {rules.data?.map((r) => (
        <article
          key={r.id}
          className={`${card} p-5 transition hover:shadow-2xl ${r.active ? '' : 'opacity-60'}`}
        >
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0 space-y-2">
              <div className="flex flex-wrap items-center gap-2">
                <span
                  className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-semibold ${
                    r.active
                      ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200'
                      : 'border-slate-200 bg-slate-100 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300'
                  }`}
                >
                  <span
                    className={`size-1.5 rounded-full ${r.active ? 'bg-emerald-500' : 'bg-slate-400'}`}
                  />
                  {r.active ? 'Đang áp dụng' : 'Đã ngừng'}
                </span>
                <span className="inline-flex items-center gap-1 rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-xs font-semibold text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300">
                  <Layers size={12} /> Ưu tiên {r.priority}
                </span>
              </div>

              <h4 className="text-base font-bold text-slate-950 dark:text-white">{r.label}</h4>
              <p className="text-2xl font-extrabold tracking-tight text-emerald-700 tabular-nums dark:text-emerald-400">
                {r.pricingUnit === 'HOURLY'
                  ? r.pricePerHour == null
                    ? 'Chưa có giá'
                    : money(r.pricePerHour)
                  : money(r.pricePerSlot)}
                <span className="ml-1 text-sm font-semibold text-slate-500 dark:text-slate-400">
                  {r.pricingUnit === 'HOURLY' ? '/giờ' : '/khung lịch cũ'}
                </span>
              </p>

              <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-sm text-slate-600 dark:text-slate-400">
                <span className="flex items-center gap-1.5">
                  <Building2 size={14} className="text-emerald-600" />
                  {r.courtId ? courts.data?.find((c) => c.id === r.courtId)?.name : 'Toàn cơ sở'}
                </span>
                <span className="flex items-center gap-1.5">
                  <Calendar size={14} className="text-emerald-600" />
                  {r.specificDate || weekdays[(r.dayOfWeek || 1) - 1]}
                </span>
                <span className="flex items-center gap-1.5">
                  <Clock size={14} className="text-emerald-600" />
                  {r.startsAt}–{r.endsAt}
                </span>
              </div>

              <p className="text-xs text-slate-500 dark:text-slate-400">
                Hiệu lực: {r.effectiveFrom} → {r.effectiveTo || 'Không giới hạn'}
              </p>
            </div>

            {r.active && (
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  disabled={busy}
                  className={outlinePill}
                  onClick={() => {
                    setEditing(r)
                    setDateMode(Boolean(r.specificDate))
                    setDraft(null)
                    document.getElementById('pricing-form')?.scrollIntoView({ behavior: 'smooth' })
                  }}
                >
                  <PencilLine className="size-4" />
                  Chỉnh sửa giá
                </button>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => disable(r.id)}
                  className={dangerPill}
                >
                  <PowerOff className="size-4" />
                  Ngừng áp dụng
                </button>
              </div>
            )}
          </div>

          <div className="mt-4 border-t border-slate-100 pt-3 dark:border-slate-800">
            <PricingHistory facility={facility.id} rule={r.id} />
          </div>
        </article>
      ))}
    </div>
  )
}
