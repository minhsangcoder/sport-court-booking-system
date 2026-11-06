'use client'
import { provinceByCode } from '@/features/facility/administrative-divisions'
import { displayLabel } from '@/lib/display-labels'
import { Donut, slotColor } from './donut-chart'
import Link from 'next/link'
import { useState, type ReactNode } from 'react'

export type BookingStats = {
  from: string
  to: string
  states: { status: string; count: number; booking_value: number }[]
  cancellations?: { by_customer: number; by_facility: number }
  courts?: { facility_id: string; court_id: string; count: number; revenue: number }[]
}

export type CourtEntry = {
  court_id: string
  facility_id: string
  facility_name: string
  address_line: string
  ward: string
  province: string
  sport: string
}

export type ReportData = {
  users: { status: string; total: number }[]
  bookings: BookingStats | null
  previous: BookingStats | null
  facilities: {
    regions: { province_code: string | null; status: string; facility_count: number }[]
  } | null
  pendingApplications: number | null
  directory: CourtEntry[] | null
}

const SUCCESS = ['CONFIRMED', 'CHECKED_IN', 'COMPLETED']
const LOST = ['CANCELLED', 'EXPIRED']
/** Fixed status order: each status keeps its color and place whatever the counts are. */
const STATUS_ORDER = ['COMPLETED', 'CONFIRMED', 'CHECKED_IN', 'PENDING', 'CANCELLED', 'EXPIRED']
export const card =
  'rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900'
export const number = (value: number) => value.toLocaleString('vi-VN')
const VND = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' })
/** Vietnamese currency format, e.g. 2.900.000 ₫. */
export const vnd = (value: number) => VND.format(Number(value) || 0)
export const sum = <T,>(items: T[], value: (item: T) => number) =>
  items.reduce((total, item) => total + Number(value(item) || 0), 0)
export const percent = (part: number, whole: number) =>
  whole ? `${Math.round((part / whole) * 100)}%` : '—'

export function Heading({ eyebrow, title }: { eyebrow: string; title: string }) {
  return (
    <div>
      <p className="text-xs font-semibold uppercase tracking-wider text-emerald-600">{eyebrow}</p>
      <h2 className="mt-1 text-xl font-extrabold tracking-tight sm:text-2xl">{title}</h2>
    </div>
  )
}

function Bar({ label, value, total }: { label: ReactNode; value: number; total: number }) {
  return (
    <li className="grid gap-2">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="font-medium">{label}</span>
        <span className="flex items-baseline gap-3 font-numeric not-italic tabular-nums">
          <strong className="min-w-[4ch] text-right font-semibold text-slate-900 dark:text-white">
            {number(value)}
          </strong>
          <span className="min-w-[4ch] text-right text-slate-500">{percent(value, total)}</span>
        </span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
        <div
          className="h-full rounded-full bg-emerald-500"
          style={{ width: total ? `${(value / total) * 100}%` : 0 }}
        />
      </div>
    </li>
  )
}

/** Booking figures of one period: everything the KPI row compares between periods. */
export function bookingFigures(stats: BookingStats | null) {
  const states = stats?.states ?? []
  const count = (status: string) =>
    sum(
      states.filter((s) => s.status === status),
      (s) => s.count,
    )
  const total = sum(states, (s) => s.count)
  const successful = states.filter((s) => SUCCESS.includes(s.status))
  const cancelled = count('CANCELLED')
  const byCustomer = Number(stats?.cancellations?.by_customer ?? 0)
  return {
    total,
    success: sum(successful, (s) => s.count),
    revenue: sum(successful, (s) => s.booking_value),
    lost: sum(
      states.filter((s) => LOST.includes(s.status)),
      (s) => s.count,
    ),
    expired: count('EXPIRED'),
    byCustomer,
    byFacility: stats?.cancellations
      ? Number(stats.cancellations.by_facility)
      : cancelled - byCustomer,
  }
}

const rate = (part: number, whole: number) => (whole ? (part / whole) * 100 : 0)

/**
 * Change against the previous period of the same length. `points` compares a rate in
 * percentage points; `lowerIsBetter` turns a rise red (cancellations).
 */
function Trend({
  current,
  previous,
  days,
  points,
  lowerIsBetter,
}: {
  current: number
  previous: number | null
  days: number
  points?: boolean
  lowerIsBetter?: boolean
}) {
  if (previous === null) return <p className="mt-2 text-xs text-slate-400">Đang so với kỳ trước…</p>
  const since = `so với ${number(days)} ngày trước`
  if (!points && previous === 0)
    return (
      <p className="mt-2 text-xs text-slate-500">
        {current ? `Kỳ trước chưa có số liệu để so` : `Không đổi ${since}`}
      </p>
    )
  const change = points ? current - previous : ((current - previous) / previous) * 100
  const shown = Math.round(change)
  if (shown === 0) return <p className="mt-2 text-xs text-slate-500">Không đổi {since}</p>
  const good = lowerIsBetter ? shown < 0 : shown > 0
  return (
    <p
      className={`mt-2 text-xs font-semibold ${good ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}
    >
      <span aria-hidden>{shown > 0 ? '▲' : '▼'} </span>
      <span className="font-numeric not-italic tabular-nums">
        {shown > 0 ? '+' : '−'}
        {Math.abs(shown)}
        {points ? '' : '%'}
      </span>
      {points ? ' điểm %' : ''} <span className="font-normal text-slate-500">{since}</span>
    </p>
  )
}

const bigNumber =
  'font-numeric font-bold not-italic leading-none tracking-tight tabular-nums text-slate-900 dark:text-white'

function Kpi({ label, value, children }: { label: string; value: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-sm font-medium text-slate-600 dark:text-slate-300">{label}</dt>
      <dd className="mt-3">
        <span className={`${bigNumber} ${value.length > 11 ? 'text-3xl' : 'text-4xl'}`}>
          {value}
        </span>
      </dd>
      {children}
    </div>
  )
}

function LossRow({ label, count, total }: { label: string; count: number; total: number }) {
  return (
    <li className="flex items-baseline justify-between gap-3">
      <span>{label}</span>
      <span className="flex items-baseline gap-2 font-numeric not-italic tabular-nums">
        <strong className="min-w-[4ch] text-right font-semibold text-slate-800 dark:text-slate-100">
          {percent(count, total)}
        </strong>
        <span className="min-w-[5ch] text-right text-slate-500">{number(count)} lượt</span>
      </span>
    </li>
  )
}

/** Headline numbers in the Home stats style, each compared with the previous period. */
export function Overview({ data }: { data: ReportData }) {
  const now = bookingFigures(data.bookings)
  const before = data.previous ? bookingFigures(data.previous) : null
  const days = data.bookings
    ? Math.max(
        1,
        Math.round((Date.parse(data.bookings.to) - Date.parse(data.bookings.from)) / 864e5),
      )
    : 30
  const regions = data.facilities?.regions ?? []
  const snapshot = [
    {
      value: number(
        sum(
          data.users.filter((u) => u.status === 'ACTIVE'),
          (u) => u.total,
        ),
      ),
      label: 'Tài khoản đang hoạt động',
      note: `trên ${number(sum(data.users, (u) => u.total))} tài khoản`,
    },
    {
      value: number(
        sum(
          regions.filter((r) => r.status === 'ACTIVE'),
          (r) => r.facility_count,
        ),
      ),
      label: 'Cơ sở đang hoạt động',
      note: `trên ${number(sum(regions, (r) => r.facility_count))} cơ sở`,
    },
    {
      value: data.pendingApplications === null ? '—' : number(data.pendingApplications),
      label: 'Hồ sơ chủ cơ sở chờ duyệt',
      note: (
        <Link
          href="/admin/owner-applications"
          className="font-semibold text-emerald-700 hover:underline"
        >
          Mở trang duyệt
        </Link>
      ),
    },
  ]
  return (
    <section className={`home-rise mt-8 p-6 sm:p-8 ${card}`}>
      <Heading eyebrow="Tổng quan" title="Nền tảng trong kỳ" />
      <dl className="mt-6 grid gap-x-8 gap-y-8 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Lượt đặt mới trong kỳ" value={number(now.total)}>
          <Trend current={now.total} previous={before?.total ?? null} days={days} />
        </Kpi>
        <Kpi label="Lượt đặt thành công" value={number(now.success)}>
          <p className="mt-1 text-xs text-slate-500">
            {percent(now.success, now.total)} số lượt đặt
          </p>
          <Trend current={now.success} previous={before?.success ?? null} days={days} />
        </Kpi>
        <Kpi label="Tổng doanh thu" value={vnd(now.revenue)}>
          <p className="mt-1 text-xs text-slate-500">Giá trị các lượt đặt thành công</p>
          <Trend current={now.revenue} previous={before?.revenue ?? null} days={days} />
        </Kpi>
        <Kpi label="Tỷ lệ hủy hoặc hết hạn" value={percent(now.lost, now.total)}>
          <ul className="mt-2 grid gap-1 text-xs text-slate-600 dark:text-slate-300">
            <LossRow label="Khách chủ động hủy" count={now.byCustomer} total={now.total} />
            <LossRow label="Quá hạn chưa thanh toán" count={now.expired} total={now.total} />
            {now.byFacility > 0 && (
              <LossRow label="Cơ sở hoặc Admin hủy" count={now.byFacility} total={now.total} />
            )}
          </ul>
          <Trend
            current={rate(now.lost, now.total)}
            previous={before ? rate(before.lost, before.total) : null}
            days={days}
            points
            lowerIsBetter
          />
        </Kpi>
      </dl>
      <dl className="mt-8 grid gap-6 border-t border-slate-100 pt-6 sm:grid-cols-3 dark:border-slate-800">
        {snapshot.map((s) => (
          <div key={s.label} className="flex flex-col">
            <dt className="text-sm font-medium text-slate-600 dark:text-slate-300">{s.label}</dt>
            <dd className="mt-2 font-numeric text-2xl font-bold not-italic tabular-nums text-slate-900 dark:text-white">
              {s.value}
            </dd>
            <p className="mt-1 text-xs text-slate-500">{s.note} · số hiện tại</p>
          </div>
        ))}
      </dl>
    </section>
  )
}

export function BookingSection({ data }: { data: ReportData }) {
  const states = data.bookings?.states ?? []
  return (
    <section className="mt-10">
      <Heading eyebrow="Lượt đặt" title="Lượt đặt theo trạng thái" />
      <div className={`home-rise mt-4 p-6 ${card}`}>
        {sum(states, (s) => s.count) ? (
          <Donut
            label="Tỷ lệ lượt đặt theo trạng thái"
            unit="lượt đặt"
            items={STATUS_ORDER.map((status, i) => ({
              key: status,
              label: displayLabel(status),
              count: sum(
                states.filter((s) => s.status === status),
                (s) => s.count,
              ),
              color: slotColor(i),
            }))}
          />
        ) : (
          <p className="text-sm text-slate-500">Chưa có lượt đặt trong kỳ.</p>
        )}
      </div>
    </section>
  )
}

export function AccountSection({ data }: { data: ReportData }) {
  const users = [...data.users].sort((a, b) => b.total - a.total)
  const total = sum(users, (u) => u.total)
  return (
    <section>
      <Heading eyebrow="Người dùng" title="Tài khoản theo trạng thái" />
      <div className={`home-rise mt-4 p-6 ${card}`}>
        {users.length ? (
          <ul className="grid gap-5">
            {users.map((u) => (
              <Bar key={u.status} label={displayLabel(u.status)} value={u.total} total={total} />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-slate-500">Chưa có tài khoản.</p>
        )}
      </div>
    </section>
  )
}

/** Facilities per province, ranked by active facilities then by total. */
export function provinceRows(data: ReportData) {
  const byProvince = new Map<string, { active: number; total: number }>()
  for (const r of data.facilities?.regions ?? []) {
    const key = r.province_code ?? ''
    const row = byProvince.get(key) ?? { active: 0, total: 0 }
    row.total += Number(r.facility_count)
    if (r.status === 'ACTIVE') row.active += Number(r.facility_count)
    byProvince.set(key, row)
  }
  return [...byProvince.entries()]
    .map(([code, r]) => ({ name: provinceByCode(code)?.name ?? 'Chưa rõ tỉnh/thành', ...r }))
    .sort((a, b) => b.active - a.active || b.total - a.total || a.name.localeCompare(b.name, 'vi'))
}

const VISIBLE_PROVINCES = 10

/** Ranked horizontal bar chart: active facilities, with the not-yet-active rest stacked after. */
export function FacilitySection({ data }: { data: ReportData }) {
  const [expanded, setExpanded] = useState(false)
  const rows = provinceRows(data)
  const active = sum(rows, (r) => r.active)
  const longest = Math.max(1, ...rows.map((r) => r.total))
  const shown = expanded ? rows : rows.slice(0, VISIBLE_PROVINCES)
  return (
    <section>
      <Heading eyebrow="Cơ sở" title="Cơ sở đang hoạt động theo tỉnh/thành" />
      <div className={`home-rise mt-4 p-6 ${card}`}>
        {rows.length ? (
          <>
            <div className="mb-4 flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-slate-500">
              <span className="flex items-center gap-2">
                <span className="size-3 rounded-sm bg-emerald-500" /> Đang hoạt động
              </span>
              <span className="flex items-center gap-2">
                <span className="size-3 rounded-sm bg-slate-300 dark:bg-slate-600" /> Chưa hoạt động
              </span>
              <span className="ml-auto">Tỷ trọng trên {number(active)} cơ sở đang hoạt động</span>
            </div>
            <ol className="grid gap-2.5">
              {shown.map((r, i) => (
                <li
                  key={r.name}
                  title={`${r.name}: ${number(r.active)} đang hoạt động, tổng ${number(r.total)} cơ sở`}
                  className="grid grid-cols-[1.5rem_minmax(0,9rem)_1fr_auto] items-center gap-3 text-sm"
                >
                  <span className="font-numeric text-right text-xs not-italic tabular-nums text-slate-400">
                    {i + 1}
                  </span>
                  <span className="truncate font-medium">{r.name}</span>
                  <span className="flex h-3 gap-0.5">
                    {r.active > 0 && (
                      <span
                        className="h-full rounded-r bg-emerald-500"
                        style={{ width: `${(r.active / longest) * 100}%` }}
                      />
                    )}
                    {r.total > r.active && (
                      <span
                        className="h-full rounded-r bg-slate-300 dark:bg-slate-600"
                        style={{ width: `${((r.total - r.active) / longest) * 100}%` }}
                      />
                    )}
                  </span>
                  <span className="flex items-baseline gap-3 font-numeric not-italic tabular-nums">
                    <strong className="min-w-[3ch] text-right font-semibold text-slate-900 dark:text-white">
                      {number(r.active)}
                    </strong>
                    <span className="min-w-[4ch] text-right text-slate-500">
                      {percent(r.active, active)}
                    </span>
                  </span>
                </li>
              ))}
            </ol>
            {rows.length > VISIBLE_PROVINCES && (
              <button
                type="button"
                onClick={() => setExpanded(!expanded)}
                className="mt-4 text-sm font-semibold text-emerald-700 hover:underline"
              >
                {expanded ? 'Thu gọn' : `Xem tất cả ${number(rows.length)} tỉnh/thành`}
              </button>
            )}
          </>
        ) : (
          <p className="text-sm text-slate-500">Chưa có cơ sở.</p>
        )}
      </div>
    </section>
  )
}
