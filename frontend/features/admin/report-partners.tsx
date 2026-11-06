'use client'
import Link from 'next/link'
import { Donut, OTHER_COLOR, slotColor } from './donut-chart'
import { card, Heading, number, percent, sum, vnd, type ReportData } from './report-sections'

const UNKNOWN_SPORT = 'Chưa rõ môn'
const MAX_SPORTS = 6

/** New bookings per sport, joined through the court directory. Sports keep alphabetical slots. */
export function sportRows(data: ReportData) {
  const sportOf = new Map((data.directory ?? []).map((c) => [c.court_id, c.sport]))
  const counts = new Map<string, number>()
  for (const c of data.bookings?.courts ?? []) {
    const sport = sportOf.get(c.court_id) ?? UNKNOWN_SPORT
    counts.set(sport, (counts.get(sport) ?? 0) + Number(c.count))
  }
  const known = [...new Set((data.directory ?? []).map((c) => c.sport))].sort((a, b) =>
    a.localeCompare(b, 'vi'),
  )
  return [...known, UNKNOWN_SPORT]
    .map((sport, i) => ({
      sport,
      count: counts.get(sport) ?? 0,
      slot: sport === UNKNOWN_SPORT ? -1 : i,
    }))
    .filter((r) => r.count > 0 || r.sport !== UNKNOWN_SPORT)
}

/** Facilities ranked by revenue (successful bookings created in the period). */
export function facilityRevenue(data: ReportData) {
  const facilityOf = new Map((data.directory ?? []).map((c) => [c.facility_id, c]))
  const totals = new Map<string, { revenue: number; count: number }>()
  for (const c of data.bookings?.courts ?? []) {
    const row = totals.get(c.facility_id) ?? { revenue: 0, count: 0 }
    row.revenue += Number(c.revenue)
    row.count += Number(c.count)
    totals.set(c.facility_id, row)
  }
  return [...totals.entries()]
    .map(([id, r]) => {
      const f = facilityOf.get(id)
      return {
        id,
        name: f?.facility_name ?? 'Cơ sở không còn trong danh mục',
        address: f ? [f.address_line, f.ward, f.province].filter(Boolean).join(', ') : '',
        ...r,
      }
    })
    .filter((r) => r.revenue > 0)
    .sort((a, b) => b.revenue - a.revenue || a.name.localeCompare(b.name, 'vi'))
}

export function SportSection({ data }: { data: ReportData }) {
  const rows = sportRows(data)
  const total = sum(rows, (r) => r.count)
  const items = rows.map((r) => ({
    key: r.sport,
    label: r.sport,
    count: r.count,
    color: r.slot >= 0 && r.slot < MAX_SPORTS ? slotColor(r.slot) : OTHER_COLOR,
  }))
  return (
    <section>
      <Heading eyebrow="Môn thể thao" title="Lượt đặt theo môn thể thao" />
      <div className={`home-rise mt-4 p-6 ${card}`}>
        {total ? (
          <Donut items={items} unit="lượt đặt" label="Tỷ lệ lượt đặt theo môn thể thao" showEmpty />
        ) : (
          <p className="text-sm text-slate-500">Chưa có lượt đặt trong kỳ.</p>
        )}
        <p className="mt-4 text-xs text-slate-500">
          Tính trên mọi lượt đặt tạo trong kỳ, theo môn của sân.
        </p>
      </div>
    </section>
  )
}

export function TopFacilitiesSection({ data }: { data: ReportData }) {
  const rows = facilityRevenue(data)
  const total = sum(rows, (r) => r.revenue)
  return (
    <section>
      <Heading eyebrow="Đối tác" title="Top 5 cơ sở doanh thu cao nhất" />
      <div className={`home-rise mt-4 p-6 ${card}`}>
        {rows.length ? (
          <ol className="grid divide-y divide-slate-100 dark:divide-slate-800">
            {rows.slice(0, 5).map((r, i) => (
              <li
                key={r.id}
                className="grid grid-cols-[1.75rem_minmax(0,1fr)_auto] items-center gap-3 py-3 first:pt-0 last:pb-0"
              >
                <span
                  className={`grid size-7 place-content-center rounded-full font-numeric text-xs font-bold not-italic tabular-nums ${i === 0 ? 'bg-emerald-600 text-white' : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'}`}
                >
                  {i + 1}
                </span>
                <span className="min-w-0">
                  <Link
                    href={`/admin/facilities/${r.id}`}
                    className="block truncate text-sm font-semibold hover:text-emerald-700 hover:underline"
                  >
                    {r.name}
                  </Link>
                  <span className="block truncate text-xs text-slate-500" title={r.address}>
                    {r.address || 'Chưa có địa chỉ'}
                  </span>
                </span>
                <span className="text-right">
                  <strong className="block font-numeric text-sm font-semibold not-italic tabular-nums text-slate-900 dark:text-white">
                    {vnd(r.revenue)}
                  </strong>
                  <span className="block font-numeric text-xs not-italic tabular-nums text-slate-500">
                    {percent(r.revenue, total)} · {number(r.count)} lượt
                  </span>
                </span>
              </li>
            ))}
          </ol>
        ) : (
          <p className="text-sm text-slate-500">Chưa có doanh thu trong kỳ.</p>
        )}
        <p className="mt-4 text-xs text-slate-500">
          Doanh thu là giá trị các lượt đặt thành công tạo trong kỳ; tỷ trọng tính trên tổng doanh
          thu.
        </p>
      </div>
    </section>
  )
}
