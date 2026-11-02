'use client'
import { PageBanner, bannerPrimary } from '@/components/page-banner'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Court, Facility } from '@/features/facility/types'
import { CourtLines, PickleBall } from '@/features/home/home-art'
import { money } from '@/features/schedule/types'
import { card, outlinePill, primaryPill } from '@/features/transfers/transfer-styles'
import { api } from '@/lib/api'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import { ArrowRight, CalendarSearch, Clock, MapPin, Plus } from 'lucide-react'
import Link from 'next/link'
import { useEffect, useMemo, useState } from 'react'
import { type Booking, statusLabels } from './types'

type Tab = 'ALL' | 'UPCOMING' | 'PENDING' | 'PAST'
type Place = { facility: Facility | null; courts: Court[] }

const tabs: { id: Tab; label: string }[] = [
  { id: 'ALL', label: 'Tất cả' },
  { id: 'UPCOMING', label: 'Sắp tới' },
  { id: 'PENDING', label: 'Chờ thanh toán' },
  { id: 'PAST', label: 'Đã qua' },
]

const tabClass = (on: boolean) =>
  `cursor-pointer whitespace-nowrap rounded-full px-4 py-2 text-sm font-semibold transition-colors ${
    on
      ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
      : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:ring-emerald-400 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700'
  }`

const statusTones: Record<string, string> = {
  PENDING:
    'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200',
  CONFIRMED:
    'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200',
  CHECKED_IN:
    'border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-500/30 dark:bg-sky-500/10 dark:text-sky-200',
  CANCELLED:
    'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-500/30 dark:bg-rose-500/10 dark:text-rose-200',
}
const quietTone =
  'border-slate-200 bg-slate-100 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300'

function tabOf(b: Booking, now: number): Exclude<Tab, 'ALL'> {
  if (b.status === 'PENDING') return 'PENDING'
  const live = b.status === 'CONFIRMED' || b.status === 'CHECKED_IN'
  return live && new Date(b.endsAt).getTime() > now ? 'UPCOMING' : 'PAST'
}

/** Booking only carries ids, so names load once per distinct facility, not once per card. */
function usePlaces(bookings: Booking[] | null) {
  const ids = useMemo(
    () => [...new Set((bookings ?? []).map((b) => b.facilityId))].sort().join(','),
    [bookings],
  )
  const [places, setPlaces] = useState<Record<string, Place>>({})
  useEffect(() => {
    if (!ids) return
    let live = true
    void Promise.all(
      ids.split(',').map(async (id) => {
        const [facility, courts] = await Promise.allSettled([
          api<Facility>(`/facilities/${id}`),
          api<Court[]>(`/facilities/${id}/courts`),
        ])
        return [
          id,
          {
            facility: facility.status === 'fulfilled' ? facility.value : null,
            courts: courts.status === 'fulfilled' ? courts.value : [],
          },
        ] as const
      }),
    ).then((rows) => {
      if (live) setPlaces(Object.fromEntries(rows))
    })
    return () => {
      live = false
    }
  }, [ids])
  return places
}

export function MyBookings() {
  return (
    <RequireAuth>
      <Loaded />
    </RequireAuth>
  )
}

function Loaded() {
  const resource = useApi<Booking[]>('/bookings')
  const places = usePlaces(resource.data)
  const [tab, setTab] = useState<Tab>('ALL')
  const [now] = useState(() => Date.now())
  const all = resource.data ?? []
  const count = (id: Tab) =>
    id === 'ALL' ? all.length : all.filter((b) => tabOf(b, now) === id).length
  const shown = tab === 'ALL' ? all : all.filter((b) => tabOf(b, now) === tab)
  return (
    <>
      <PageBanner
        eyebrow="Lịch chơi của bạn"
        title="Lịch đặt của tôi"
        description="Theo dõi trạng thái thanh toán, mã vào sân và chuyển nhượng lượt đặt."
        icon="tennis"
        actions={
          <Link className={bannerPrimary} href="/search">
            <Plus className="size-4" strokeWidth={2} />
            Đặt sân mới
          </Link>
        }
      />
      <div className="mx-auto max-w-6xl px-4 py-7 sm:px-6 sm:py-9">
        {resource.loading && !resource.data && (
          <div role="status" className="grid gap-4">
            <span className="sr-only">Đang tải lịch đặt…</span>
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className={`${card} h-28 animate-pulse bg-slate-100 dark:bg-slate-800`}
              />
            ))}
          </div>
        )}
        {resource.error && (
          <div role="alert" className={`${card} home-rise p-6`}>
            <p className="text-sm font-semibold text-red-700 dark:text-red-300">{resource.error}</p>
            <button type="button" className={`${outlinePill} mt-4`} onClick={resource.reload}>
              Thử lại
            </button>
          </div>
        )}
        {resource.data?.length === 0 && (
          <section className={`${card} home-rise p-8 text-center sm:p-12`}>
            <CourtLines className="pointer-events-none absolute -right-20 -top-12 w-80 -rotate-6 text-emerald-600/[0.06]" />
            <PickleBall className="home-float mx-auto w-14" />
            <h2 className="mt-5 text-xl font-extrabold tracking-tight text-slate-950 dark:text-white">
              Chưa có lịch đặt
            </h2>
            <p className="mt-2 text-sm text-slate-600 dark:text-slate-400">
              Chọn một sân để bắt đầu buổi chơi tiếp theo.
            </p>
            <Link className={`${primaryPill} mt-6`} href="/search">
              Tìm sân <ArrowRight className="size-4" />
            </Link>
          </section>
        )}
        {all.length > 0 && (
          <>
            <div className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
              {tabs.map((t) => (
                <button
                  key={t.id}
                  type="button"
                  aria-pressed={tab === t.id}
                  className={tabClass(tab === t.id)}
                  onClick={() => setTab(t.id)}
                >
                  {t.label} ({count(t.id)})
                </button>
              ))}
            </div>
            {shown.length === 0 ? (
              <div className="mt-6 flex flex-col items-center gap-3 rounded-3xl border border-dashed border-slate-300 bg-white/60 p-10 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900/60 dark:text-slate-400">
                <CalendarSearch className="size-6 text-emerald-600" strokeWidth={1.6} />
                Không có lượt đặt nào trong mục này.
              </div>
            ) : (
              <ul className="mt-6 grid gap-4">
                {shown.map((b, i) => (
                  <li
                    key={b.id}
                    className="home-rise"
                    style={{ animationDelay: `${Math.min(i, 8) * 50}ms` }}
                  >
                    <BookingCard booking={b} place={places[b.facilityId]} now={now} />
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </>
  )
}

function BookingCard({ booking: b, place, now }: { booking: Booking; place?: Place; now: number }) {
  const timeZone = b.priceSnapshot.timezone
  const start = new Date(b.startsAt)
  const part = (options: Intl.DateTimeFormatOptions) =>
    start.toLocaleDateString('vi-VN', { timeZone, ...options })
  const time = (value: string) =>
    new Date(value).toLocaleTimeString('vi-VN', { timeZone, hour: '2-digit', minute: '2-digit' })
  const past = tabOf(b, now) === 'PAST'
  const court = place?.courts.find((c) => c.id === b.courtId)?.name
  const facility = place?.facility
  return (
    <Link
      href={`/customer/bookings/${b.id}`}
      className={`${card} group flex flex-wrap items-center gap-x-5 gap-y-4 p-4 transition hover:-translate-y-0.5 hover:border-emerald-500/50 hover:shadow-2xl sm:flex-nowrap sm:p-5`}
    >
      <CourtLines className="pointer-events-none absolute -right-16 -top-10 w-64 -rotate-6 text-emerald-600/[0.05]" />
      <div
        className={`flex size-[72px] shrink-0 flex-col items-center justify-center rounded-2xl shadow-md ${
          past
            ? 'bg-slate-200 text-slate-700 dark:bg-slate-800 dark:text-slate-200'
            : 'bg-emerald-700 text-white'
        }`}
      >
        <span className="text-[11px] font-semibold uppercase">{part({ weekday: 'short' })}</span>
        <span className="text-2xl font-extrabold leading-none tabular-nums">
          {part({ day: '2-digit' })}
        </span>
        <span className="mt-0.5 text-[11px] font-medium tabular-nums">
          {start.toLocaleDateString('en-GB', { timeZone, month: '2-digit', year: 'numeric' })}
        </span>
      </div>
      <div className="min-w-0 flex-1 basis-48">
        <h2 className="truncate text-base font-bold text-slate-950 transition-colors group-hover:text-emerald-700 sm:text-lg dark:text-white dark:group-hover:text-emerald-400">
          {facility?.name || 'Lượt đặt sân'}
          {court ? ` · ${court}` : ''}
        </h2>
        <p className="mt-1.5 flex items-center gap-1.5 text-sm font-semibold text-slate-700 tabular-nums dark:text-slate-300">
          <Clock className="size-3.5 shrink-0 text-emerald-600" />
          {time(b.startsAt)} – {time(b.endsAt)}
        </p>
        {facility && (
          <p className="mt-1 flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
            <MapPin className="size-3.5 shrink-0" />
            <span className="truncate">
              {facility.ward}, {facility.province}
            </span>
          </p>
        )}
        <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
          Mã đặt: <span className="font-mono">{b.id.slice(0, 8).toUpperCase()}</span> ·{' '}
          {b.source === 'OFFLINE_COUNTER' ? 'Tại quầy' : 'Trực tuyến'}
        </p>
      </div>
      <div className="flex w-full items-center justify-between gap-4 border-t border-slate-100 pt-3 sm:w-auto sm:shrink-0 sm:flex-col sm:items-end sm:border-0 sm:pt-0 dark:border-slate-800">
        <span
          className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-semibold ${statusTones[b.status] ?? quietTone}`}
        >
          {b.status === 'CHECKED_IN' && (
            <span className="relative flex size-2">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-sky-400 opacity-75" />
              <span className="relative inline-flex size-2 rounded-full bg-sky-500" />
            </span>
          )}
          {statusLabels[b.status] || displayLabel(b.status)}
        </span>
        <span className="flex items-center gap-2 text-base font-extrabold text-slate-950 tabular-nums sm:text-lg dark:text-white">
          {money(b.amount, b.currency)}
          <ArrowRight className="size-4 text-slate-400 transition group-hover:translate-x-0.5 group-hover:text-emerald-600" />
        </span>
      </div>
    </Link>
  )
}
