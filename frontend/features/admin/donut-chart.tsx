'use client'
import { useState } from 'react'

export type DonutItem = { key: string; label: string; count: number; color: string }

const R = 80
const C = 2 * Math.PI * R
const GAP = 2
const share = (part: number, whole: number) =>
  whole ? `${Math.round((part / whole) * 100)}%` : '—'

/**
 * Part-to-whole donut with a legend beside it. Items are drawn in the given order, so
 * callers keep each entity's color and position fixed whatever the counts are.
 */
export function Donut({
  items,
  unit,
  label,
  showEmpty = false,
}: {
  items: DonutItem[]
  unit: string
  label: string
  /** Keep zero-count items in the legend (a fixed set such as sports), not in the ring. */
  showEmpty?: boolean
}) {
  const [active, setActive] = useState<string | null>(null)
  const rows = items.filter((s) => s.count > 0)
  const total = rows.reduce((t, s) => t + s.count, 0)
  const arcs = rows.map((s, i) => ({
    ...s,
    length: (s.count / total) * C,
    offset: (rows.slice(0, i).reduce((t, r) => t + r.count, 0) / total) * C,
  }))
  const focus = rows.find((s) => s.key === active)
  return (
    <div className="grid items-center gap-8 md:grid-cols-[13rem_1fr]">
      <div className="relative mx-auto size-52">
        <svg viewBox="0 0 200 200" className="size-full -rotate-90" role="img" aria-label={label}>
          <circle
            cx="100"
            cy="100"
            r={R}
            fill="none"
            strokeWidth="24"
            className="stroke-slate-100 dark:stroke-slate-800"
          />
          {arcs.map((a) => (
            <circle
              key={a.key}
              cx="100"
              cy="100"
              r={R}
              fill="none"
              strokeWidth={active === a.key ? 30 : 24}
              stroke={a.color}
              strokeDasharray={`${Math.max(a.length - (rows.length > 1 ? GAP : 0), 0.5)} ${C}`}
              strokeDashoffset={-a.offset}
              opacity={active && active !== a.key ? 0.35 : 1}
              className="cursor-pointer transition-all"
              onMouseEnter={() => setActive(a.key)}
              onMouseLeave={() => setActive(null)}
            >
              <title>{`${a.label}: ${a.count.toLocaleString('vi-VN')} ${unit} (${share(a.count, total)})`}</title>
            </circle>
          ))}
        </svg>
        <div className="pointer-events-none absolute inset-0 grid place-content-center text-center">
          <span className="font-numeric text-3xl font-bold not-italic tabular-nums text-slate-900 dark:text-white">
            {(focus ? focus.count : total).toLocaleString('vi-VN')}
          </span>
          <span className="mt-1 max-w-[8rem] text-xs text-slate-500">
            {focus ? `${focus.label} · ${share(focus.count, total)}` : unit}
          </span>
        </div>
      </div>
      <ul className="grid gap-1">
        {(showEmpty ? items : rows).map((s) => (
          <li
            key={s.key}
            onMouseEnter={() => setActive(s.key)}
            onMouseLeave={() => setActive(null)}
            className={`flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition-colors ${active === s.key ? 'bg-slate-100 dark:bg-slate-800' : ''}`}
          >
            <span className="size-3 shrink-0 rounded-sm" style={{ background: s.color }} />
            <span className="flex-1 font-medium">{s.label}</span>
            <span className="flex items-baseline gap-3 font-numeric not-italic tabular-nums">
              <strong className="min-w-[4ch] text-right font-semibold text-slate-900 dark:text-white">
                {s.count.toLocaleString('vi-VN')}
              </strong>
              <span className="min-w-[4ch] text-right text-slate-500">{share(s.count, total)}</span>
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export const OTHER_COLOR = 'rgb(148 163 184)'
/** Categorical slot `index` (0-based) of the validated report palette; past six, gray. */
export const slotColor = (index: number) =>
  index >= 0 && index < 6 ? `var(--viz-${index + 1})` : OTHER_COLOR
