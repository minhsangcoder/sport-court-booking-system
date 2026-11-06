'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
import { AdminPage } from './admin-nav'
import { exportReport } from './report-export'
import { SportSection, TopFacilitiesSection } from './report-partners'
import {
  PRESETS,
  presetRange,
  previousPath,
  rangeQuery,
  type DayRange,
  type PresetKey,
} from './report-range'
import {
  AccountSection,
  BookingSection,
  FacilitySection,
  Overview,
  type ReportData,
} from './report-sections'

export function ReportPage() {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Loaded />
    </RequireAuth>
  )
}

const card =
  'rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900'

function Loaded() {
  const [range, setRange] = useState<DayRange>(() => presetRange('30d'))
  const [preset, setPreset] = useState<PresetKey | null>('30d')
  const [query, setQuery] = useState(() => rangeQuery(presetRange('30d')))
  const users = useApi<ReportData['users']>('/admin/accounts/statistics')
  const bookings = useApi<ReportData['bookings']>(`/admin/bookings/statistics?${query}`)
  const previous = useApi<ReportData['bookings']>(previousPath(bookings.data))
  const facilities = useApi<ReportData['facilities']>('/admin/facilities/statistics')
  const applications = useApi<{ totalElements: number }>(
    '/admin/owner-applications/page?state=PENDING_APPROVAL&size=1',
  )
  const directory = useApi<ReportData['directory']>('/admin/facilities/court-directory')
  const all = [users, bookings, previous, facilities, applications, directory]
  const loading = all.some((r) => r.loading)
  const error = all.find((r) => r.error)?.error
  const data: ReportData = {
    users: users.data ?? [],
    bookings: bookings.data ?? null,
    previous: previous.data ?? null,
    facilities: facilities.data ?? null,
    pendingApplications: applications.data?.totalElements ?? null,
    directory: directory.data ?? null,
  }
  const ready = !loading && !error && data.bookings !== null
  const choose = (key: PresetKey) => {
    const next = presetRange(key)
    setPreset(key)
    setRange(next)
    setQuery(rangeQuery(next))
  }
  return (
    <AdminPage
      active="reports"
      title="Báo cáo hoạt động nền tảng"
      description="Lượt đặt, doanh thu đặt sân, người dùng và cơ sở trên SportHub, so với kỳ trước cùng độ dài."
      actions={
        <>
          <Button
            onClick={() => exportReport(data, 'xlsx')}
            disabled={!ready}
            className="rounded-full bg-emerald-600 px-5 text-white hover:bg-emerald-500"
          >
            Xuất Excel
          </Button>
          <Button
            variant="outline"
            onClick={() => exportReport(data, 'csv')}
            disabled={!ready}
            className="rounded-full border-white/30 bg-white/10 px-5 text-white hover:bg-white/20"
          >
            Xuất CSV
          </Button>
        </>
      }
    >
      <form
        onSubmit={(e) => {
          e.preventDefault()
          setPreset(null)
          setQuery(rangeQuery(range))
        }}
        className={`home-rise grid gap-4 p-5 ${card}`}
      >
        <div className="flex flex-wrap gap-2" role="group" aria-label="Chọn nhanh kỳ báo cáo">
          {PRESETS.map((p) => (
            <button
              key={p.key}
              type="button"
              aria-pressed={preset === p.key}
              onClick={() => choose(p.key)}
              className={`rounded-full border px-4 py-1.5 text-sm font-semibold transition-colors ${
                preset === p.key
                  ? 'border-emerald-600 bg-emerald-600 text-white'
                  : 'border-slate-200 text-slate-700 hover:border-emerald-500 hover:text-emerald-700 dark:border-slate-700 dark:text-slate-200'
              }`}
            >
              {p.label}
            </button>
          ))}
        </div>
        <div className="flex flex-wrap items-end gap-4">
          <label className="grid gap-2 text-sm font-medium">
            Từ ngày
            <Input
              name="from"
              type="date"
              value={range.from}
              max={range.to}
              required
              onChange={(e) => setRange({ ...range, from: e.target.value })}
            />
          </label>
          <label className="grid gap-2 text-sm font-medium">
            Đến hết ngày
            <Input
              name="to"
              type="date"
              value={range.to}
              min={range.from}
              required
              onChange={(e) => setRange({ ...range, to: e.target.value })}
            />
          </label>
          <Button type="submit" variant="outline" className="rounded-full px-5">
            Xem kỳ này
          </Button>
          <p className="text-xs text-slate-500">
            Lượt đặt tính theo lúc tạo, giờ Việt Nam; tài khoản và cơ sở là số hiện tại.
          </p>
        </div>
      </form>
      {loading && (
        <p role="status" className="mt-6 text-sm text-slate-500">
          Đang tổng hợp số liệu…
        </p>
      )}
      {error && (
        <p role="alert" className="mt-6 text-sm text-red-700">
          {error}
          <button className="ml-4 underline" onClick={() => all.forEach((r) => r.reload())}>
            Thử lại
          </button>
        </p>
      )}
      <Overview data={data} />
      <BookingSection data={data} />
      <div className="mt-10 grid gap-10 lg:grid-cols-2">
        <div className="grid content-start gap-10">
          <AccountSection data={data} />
          <SportSection data={data} />
        </div>
        <div className="grid content-start gap-10">
          <FacilitySection data={data} />
          <TopFacilitiesSection data={data} />
        </div>
      </div>
    </AdminPage>
  )
}
