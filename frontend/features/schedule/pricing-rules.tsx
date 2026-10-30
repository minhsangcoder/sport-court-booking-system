'use client'
import { Button } from '@/components/ui/button'
import { PricingHistory } from '@/features/schedule/pricing-history'
import { money, weekdays } from '@/features/schedule/types'
import type { usePricing } from '@/features/schedule/use-pricing'
import { Building2, Calendar, Clock, Layers } from 'lucide-react'

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
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-bold uppercase tracking-wider text-slate-700 dark:text-slate-300">
          Danh sách quy tắc giá ({rules.data?.length ?? 0})
        </h3>
      </div>
      {rules.data?.map((r) => (
        <article
          key={r.id}
          className={`relative rounded-2xl border bg-white/90 p-5 shadow-xs transition-all duration-200 hover:shadow-md dark:bg-slate-900/90 ${
            r.active
              ? 'border-slate-200/90 dark:border-slate-800'
              : 'border-slate-200/50 opacity-60 dark:border-slate-800/50'
          }`}
        >
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="space-y-2">
              <div className="flex flex-wrap items-center gap-2.5">
                <span
                  className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] font-bold ${
                    r.active
                      ? 'border border-emerald-500/30 bg-emerald-50 text-emerald-700 dark:border-emerald-500/40 dark:bg-emerald-950/60 dark:text-emerald-300'
                      : 'border border-slate-200 bg-slate-100 text-slate-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400'
                  }`}
                >
                  <span
                    className={`size-1.5 rounded-full ${r.active ? 'bg-emerald-500' : 'bg-slate-400'}`}
                  />
                  {r.active ? 'Đang áp dụng' : 'Đã ngừng'}
                </span>
                <span className="inline-flex items-center gap-1 rounded-full border border-slate-200 bg-slate-50 px-2.5 py-0.5 text-[11px] font-semibold text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400">
                  <Layers size={11} /> Ưu tiên {r.priority}
                </span>
              </div>

              <h3 className="text-base font-bold text-slate-900 dark:text-white">
                {r.label} ·{' '}
                <span className="text-emerald-600 dark:text-emerald-400">
                  {r.pricingUnit === 'HOURLY'
                    ? r.pricePerHour == null
                      ? 'Chưa có giá'
                      : money(r.pricePerHour)
                    : money(r.pricePerSlot)}
                  {r.pricingUnit === 'HOURLY' ? '/giờ' : '/khung lịch cũ'}
                </span>
              </h3>

              <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-slate-500 dark:text-slate-400">
                <span className="flex items-center gap-1.5">
                  <Building2 size={13} className="text-slate-400" />
                  {r.courtId ? courts.data?.find((c) => c.id === r.courtId)?.name : 'Toàn cơ sở'}
                </span>
                <span className="flex items-center gap-1.5">
                  <Calendar size={13} className="text-slate-400" />
                  {r.specificDate || weekdays[(r.dayOfWeek || 1) - 1]}
                </span>
                <span className="flex items-center gap-1.5">
                  <Clock size={13} className="text-slate-400" />
                  {r.startsAt}–{r.endsAt}
                </span>
              </div>

              <p className="text-[11px] text-slate-400 dark:text-slate-500">
                Hiệu lực: {r.effectiveFrom} → {r.effectiveTo || 'Không giới hạn'}
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              {r.active && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  className="rounded-xl border-slate-200 bg-white font-bold text-slate-700 shadow-2xs hover:bg-slate-50 hover:border-slate-300 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
                  onClick={() => {
                    setEditing(r)
                    setDateMode(Boolean(r.specificDate))
                    setDraft(null)
                  }}
                >
                  Chỉnh sửa giá
                </Button>
              )}
              {r.active && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => disable(r.id)}
                  className="rounded-xl border border-red-200 bg-red-50/60 px-3.5 py-1.5 text-xs font-bold text-red-700 shadow-2xs transition-all hover:bg-red-100 hover:border-red-300 dark:border-red-900/40 dark:bg-red-950/40 dark:text-red-300"
                >
                  Ngừng áp dụng
                </button>
              )}
            </div>
          </div>

          <div className="mt-4 border-t border-slate-100 pt-3 dark:border-slate-800">
            <PricingHistory facility={facility.id} rule={r.id} />
          </div>
        </article>
      ))}
    </div>
  )
}
