'use client'
import type { useOperationalDetail } from '@/features/operations/use-operational-detail'
import { money } from '@/features/schedule/types'
import { card } from '@/features/transfers/transfer-styles'
import { displayLabel } from '@/lib/display-labels'
import { History } from 'lucide-react'

export function OperationPrice({
  model,
}: {
  model: Pick<ReturnType<typeof useOperationalDetail>, 'b'>
}) {
  const { b } = model
  if (!b) return null
  const timeZone = b.priceSnapshot.timezone
  return (
    <aside className={`${card} home-rise space-y-4 p-5 sm:p-6 lg:sticky lg:top-6`}>
      <div>
        <p className="text-xs font-semibold uppercase tracking-wider text-emerald-700 dark:text-emerald-400">
          Giá chốt
        </p>
        <strong className="mt-1 block text-4xl font-extrabold tracking-tight text-emerald-700 tabular-nums dark:text-emerald-400">
          {money(b.amount, b.currency)}
        </strong>
      </div>
      <p className="text-sm leading-relaxed text-slate-600 dark:text-slate-400">
        Nguồn {displayLabel(b.source)} · Giá chốt giữ nguyên sau thay đổi bảng giá hoặc chuyển
        nhượng.
      </p>
      <details className="rounded-2xl border border-slate-200/80 bg-slate-50/80 p-3.5 text-sm dark:border-slate-800 dark:bg-slate-800/50">
        <summary className="cursor-pointer font-semibold text-emerald-700 dark:text-emerald-400">
          Giá từng khung giờ tại lúc đặt
        </summary>
        {b.priceSnapshot.segments.map((segment) => (
          <p
            className="mt-2 flex justify-between gap-3 text-slate-700 tabular-nums dark:text-slate-300"
            key={segment.startsAt}
          >
            <span>
              {new Date(segment.startsAt).toLocaleTimeString('vi-VN', {
                timeZone,
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
            <span className="font-semibold">{money(segment.amount ?? 0, segment.currency)}</span>
          </p>
        ))}
      </details>
    </aside>
  )
}

export function OperationHistory({
  model,
}: {
  model: Pick<ReturnType<typeof useOperationalDetail>, 'b' | 'r' | 'actorLabel'>
}) {
  const { b, r, actorLabel } = model
  if (!b || !r.data) return null
  return (
    <section className={`${card} home-rise p-5 sm:p-6`}>
      <h3 className="flex items-center gap-2 text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
        <History className="size-5 text-emerald-600" />
        Lịch sử vận hành
      </h3>
      <ol className="mt-4 space-y-4 border-l-2 border-emerald-100 pl-4 dark:border-emerald-950">
        {r.data.statusAudit.map((entry) => {
          const source = displayLabel(entry.changeSource, '')
          return (
            <li key={entry.id} className="relative text-sm">
              <span className="absolute -left-[23px] top-1 size-2.5 rounded-full border-2 border-white bg-emerald-500 dark:border-slate-900" />
              <strong className="text-slate-950 dark:text-white">
                {entry.previousStatus ? displayLabel(entry.previousStatus) + ' → ' : ''}
                {displayLabel(entry.status)}
              </strong>
              <p className="mt-0.5 text-slate-600 tabular-nums dark:text-slate-400">
                {new Date(entry.recordedAt).toLocaleString('vi-VN', {
                  timeZone: b.priceSnapshot.timezone,
                })}
                {source ? ` · ${source}` : ''} · Phiên bản {entry.bookingVersion}
              </p>
              {entry.actorId && (
                <p className="text-xs text-slate-500 dark:text-slate-400" title={entry.actorId}>
                  Người thực hiện: {actorLabel(entry.actorId)}
                </p>
              )}
            </li>
          )
        })}
      </ol>
    </section>
  )
}
