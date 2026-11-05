'use client'
import { type PriceRule, money } from '@/features/schedule/types'
import { useApi } from '@/lib/use-api'
import { History } from 'lucide-react'
import { useState } from 'react'

export function PricingHistory({ facility, rule }: { facility: string; rule: string }) {
  const [open, setOpen] = useState(false)
  const r = useApi<
    { ruleId: string; version: number; snapshot: PriceRule; actorId: string; recordedAt: string }[]
  >(open ? `/schedules/facilities/${facility}/pricing/${rule}/history` : null)

  return (
    <details onToggle={(e) => setOpen(e.currentTarget.open)} className="w-full text-xs">
      <summary className="inline-flex cursor-pointer items-center gap-1.5 font-semibold text-emerald-700 hover:text-emerald-800 transition-colors select-none dark:text-emerald-400">
        <History size={13} />
        Lịch sử quy tắc giá
      </summary>
      {r.loading && (
        <p role="status" className="mt-2 text-slate-500">
          Đang tải phiên bản…
        </p>
      )}
      {r.error && (
        <p role="alert" className="mt-2 text-red-700">
          {r.error}
        </p>
      )}
      <ol className="mt-3 space-y-2.5 border-l-2 border-emerald-100 pl-3.5 dark:border-emerald-950">
        {r.data?.map((version) => (
          <li key={version.version} className="relative space-y-1">
            <span className="absolute -left-[19px] top-1 size-2 rounded-full border-2 border-white bg-emerald-500 dark:border-slate-900" />
            <strong className="block font-bold text-slate-800 dark:text-slate-200">
              Phiên bản {version.version}:{' '}
              {version.snapshot.pricingUnit === 'HOURLY'
                ? version.snapshot.pricePerHour == null
                  ? 'Chưa có giá'
                  : money(version.snapshot.pricePerHour) + '/giờ'
                : money(version.snapshot.pricePerSlot) + '/khung lịch cũ'}
            </strong>
            <p className="text-slate-500 dark:text-slate-400">
              {new Date(version.recordedAt).toLocaleString('vi-VN')} ·{' '}
              {version.snapshot.active ? 'Đang áp dụng' : 'Đã ngừng'} ·{' '}
              {version.snapshot.effectiveFrom} → {version.snapshot.effectiveTo || 'Không giới hạn'}
            </p>
          </li>
        ))}
      </ol>
    </details>
  )
}
