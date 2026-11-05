'use client'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { BannerPage } from '@/components/page-banner'
import { RecordDetails } from '@/components/record-details'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import { type Booking, type BookingDetail, statusLabels } from '@/features/booking/types'
import { money } from '@/features/schedule/types'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useCallback, useEffect, useState } from 'react'
import type { Court } from '@/features/facility/types'
import { AdminPage } from './admin-nav'
import { FacilityPicker, facilityCode, type PickedFacility } from './facility-picker'
export function BookingMonitor() {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Loaded />
    </RequireAuth>
  )
}
const SCOPE_KEY = 'sporthub.admin.bookings.scope'
const CARD =
  'rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise'
type PickedCourt = Pick<Court, 'id' | 'name' | 'code'>
type Scope = { facility: PickedFacility; court: PickedCourt | null }
function Loaded() {
  // Admin browses bookings facility → court → bookings; the choice survives opening a booking detail.
  // RequireAuth renders this only in the browser, after the session is ready.
  const [scope, setScopeState] = useState<Scope | null>(() => {
    try {
      const saved = sessionStorage.getItem(SCOPE_KEY)
      return saved ? (JSON.parse(saved) as Scope) : null
    } catch {
      return null
    }
  })
  function setScope(next: Scope | null) {
    setScopeState(next)
    if (next) sessionStorage.setItem(SCOPE_KEY, JSON.stringify(next))
    else sessionStorage.removeItem(SCOPE_KEY)
  }
  const facility = scope?.facility
  const pickCourt = useCallback(
    (court: PickedCourt) => facility && setScope({ facility, court }),
    [facility],
  )
  return (
    <AdminPage active="bookings" title="Giám sát lượt đặt">
      {!scope ? (
        <section className={`mt-6 p-5 ${CARD}`}>
          <h2 className="text-lg font-extrabold tracking-tight">Bước 1 · Chọn cơ sở</h2>
          <p className="mt-1 mb-4 text-sm text-slate-500">
            Lượt đặt được xem theo từng sân của một cơ sở.
          </p>
          <FacilityPicker onPick={(picked) => setScope({ facility: picked, court: null })} />
        </section>
      ) : !scope.court ? (
        <CourtStep facility={scope.facility} onBack={() => setScope(null)} onPick={pickCourt} />
      ) : (
        <CourtBookings
          facility={scope.facility}
          court={scope.court}
          onChangeCourt={() => setScope({ facility: scope.facility, court: null })}
          onChangeFacility={() => setScope(null)}
        />
      )}
    </AdminPage>
  )
}
function CourtStep({
  facility,
  onBack,
  onPick,
}: {
  facility: PickedFacility
  onBack: () => void
  onPick: (court: PickedCourt) => void
}) {
  const courts = useApi<Court[]>(`/admin/facilities/${facility.id}/courts`)
  const only = courts.data?.length === 1 ? courts.data[0] : null
  // A facility with a single court has nothing to choose: go straight to its bookings.
  useEffect(() => {
    if (only) onPick({ id: only.id, name: only.name, code: only.code })
  }, [only, onPick])
  return (
    <section className={`mt-6 p-5 ${CARD}`}>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-extrabold tracking-tight">Bước 2 · Chọn sân</h2>
          <p className="mt-1 text-sm text-slate-500">
            Mã {facilityCode(facility.id)} · {facility.name} · {displayLabel(facility.status)}
          </p>
        </div>
        <Button variant="outline" onClick={onBack}>
          Đổi cơ sở
        </Button>
      </div>
      {courts.loading && (
        <p role="status" className="mt-5 text-sm">
          Đang tải danh sách sân…
        </p>
      )}
      {courts.error && (
        <p role="alert" className="mt-5 text-sm text-red-700">
          {courts.error}
          <button type="button" className="ml-3 underline" onClick={courts.reload}>
            Thử lại
          </button>
        </p>
      )}
      {courts.data?.length === 0 && (
        <p className="mt-5 text-sm text-slate-500">Cơ sở này chưa có sân nào.</p>
      )}
      <ul className="mt-5 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {courts.data?.map((c) => (
          <li key={c.id}>
            <button
              type="button"
              onClick={() => onPick({ id: c.id, name: c.name, code: c.code })}
              className="w-full rounded-2xl border border-slate-200 px-4 py-3 text-left transition hover:border-emerald-400 hover:bg-emerald-50 focus-visible:border-emerald-500 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-emerald-500/20 dark:border-slate-700 dark:hover:bg-emerald-950"
            >
              <p className="font-semibold">{c.name}</p>
              <p className="mt-0.5 text-xs text-slate-500">
                Mã sân {c.code}
                {!c.enabled && ' · Đang tạm ngừng'}
              </p>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
function CourtBookings({
  facility,
  court,
  onChangeCourt,
  onChangeFacility,
}: {
  facility: PickedFacility
  court: PickedCourt
  onChangeCourt: () => void
  onChangeFacility: () => void
}) {
  const [filters, setFilters] = useState('')
  const query = new URLSearchParams([
    ['facilityId', facility.id],
    ['courtId', court.id],
    ...new URLSearchParams(filters),
  ])
  const r = useApi<Booking[]>(`/admin/bookings?${query}`)
  function search(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = new FormData(e.currentTarget),
      p = new URLSearchParams()
    for (const key of ['id', 'status']) {
      const v = String(f.get(key) || '').trim()
      if (v) p.set(key, v)
    }
    setFilters(p.toString())
  }
  return (
    <>
      <section className={`mt-6 flex flex-wrap items-center justify-between gap-4 p-5 ${CARD}`}>
        <div>
          <p className="text-sm text-slate-500">Đang xem</p>
          <p className="mt-1 text-lg font-bold">
            {facility.name} › {court.name}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            Mã cơ sở {facilityCode(facility.id)} · Mã sân {court.code}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={onChangeCourt}>
            Đổi sân
          </Button>
          <Button variant="outline" onClick={onChangeFacility}>
            Đổi cơ sở
          </Button>
        </div>
      </section>
      <form
        key={court.id}
        onSubmit={search}
        className={`mt-6 grid gap-4 p-5 md:grid-cols-3 ${CARD}`}
      >
        <label className="grid gap-2 text-sm">
          Mã lượt đặt
          <Input name="id" placeholder="Mã định danh lượt đặt" />
        </label>
        <label className="grid gap-2 text-sm">
          Trạng thái
          <select name="status" className="h-11 rounded-xl border bg-white px-3.5">
            <option value="">Tất cả</option>
            {['PENDING', 'CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'EXPIRED'].map(
              (s) => (
                <option value={s} key={s}>
                  {statusLabels[s]}
                </option>
              ),
            )}
          </select>
        </label>
        <Button type="submit" className="mt-auto bg-emerald-600 text-white">
          Tra cứu
        </Button>
      </form>
      {r.loading && (
        <p role="status" className="mt-6">
          Đang tải lượt đặt…
        </p>
      )}
      {r.error && (
        <p role="alert" className="mt-6 text-red-700">
          {r.error}
          <button className="ml-4 underline" onClick={r.reload}>
            Thử lại
          </button>
        </p>
      )}
      {r.data?.length === 0 && (
        <p className={`mt-6 p-6 text-slate-500 ${CARD}`}>Sân này chưa có lượt đặt phù hợp.</p>
      )}
      {!!r.data?.length && (
        <div className={`mt-6 divide-y ${CARD}`}>
          {r.data.map((b) => (
            <Link
              key={b.id}
              href={`/admin/bookings/${b.id}`}
              className="flex flex-wrap justify-between gap-4 p-5 hover:bg-emerald-50"
            >
              <div>
                <p className="font-bold">
                  {b.id.slice(0, 8).toUpperCase()} · {statusLabels[b.status]}
                </p>
                <p className="mt-2 text-sm text-slate-500">
                  {new Date(b.startsAt).toLocaleString('vi-VN', {
                    timeZone: b.priceSnapshot.timezone,
                  })}
                </p>
              </div>
              <strong className="text-emerald-700">{money(b.amount, b.currency)}</strong>
            </Link>
          ))}
        </div>
      )}
      <p className="mt-4 text-xs text-slate-500">Tối đa 200 lượt đặt, mới nhất theo giờ chơi.</p>
    </>
  )
}
export function AdminBookingDetail({ id }: { id: string }) {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Detail id={id} />
    </RequireAuth>
  )
}
function Detail({ id }: { id: string }) {
  const r = useApi<BookingDetail>(`/admin/bookings/${id}`)
  if (r.loading)
    return (
      <p role="status" className="p-8">
        Đang tải lịch sử lượt đặt…
      </p>
    )
  if (!r.data)
    return (
      <p className="p-8 text-red-700" role="alert">
        {r.error}
        <button className="ml-4 underline" onClick={r.reload}>
          Thử lại
        </button>
      </p>
    )
  const b = r.data.booking
  return (
    <BannerPage
      eyebrow={
        <Link className={bannerLink} href="/admin/bookings">
          Giám sát lượt đặt / {id.slice(0, 8).toUpperCase()}
        </Link>
      }
      title={statusLabels[b.status] ?? ''}
      width="max-w-4xl"
    >
      <section className="mt-6 space-y-3 p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
        <p className="font-bold text-emerald-700">Giá gốc: {money(b.amount, b.currency)}</p>
        <p>
          {new Date(b.startsAt).toLocaleString('vi-VN', { timeZone: b.priceSnapshot.timezone })} →{' '}
          {new Date(b.endsAt).toLocaleTimeString('vi-VN', { timeZone: b.priceSnapshot.timezone })}
        </p>
        <p className="break-all text-sm text-slate-500">
          Cơ sở {b.facilityId} · Sân {b.courtId}
        </p>
        <p className="break-all text-sm text-slate-500">
          Người giữ quyền sử dụng hiện tại: {b.currentHolderId ?? 'Không có (Khách vãng lai)'}
        </p>
        {b.paymentId && (
          <Link
            href={`/admin/payments/${b.paymentId}`}
            className="block text-sm font-semibold text-emerald-700 hover:underline"
          >
            Giao dịch thanh toán gốc
          </Link>
        )}
        <details>
          <summary className="text-sm">Chi tiết giá tại lúc đặt</summary>
          <div className="mt-4 overflow-x-auto rounded-2xl bg-slate-50 p-4 text-xs">
            <RecordDetails value={b.priceSnapshot} />
          </div>
        </details>
      </section>
      <h2 className="mt-8 text-lg font-extrabold tracking-tight">
        Lịch sử trạng thái và chuyển quyền
      </h2>
      <ol className="mt-5 space-y-4 border-l-2 border-emerald-100 pl-5">
        {r.data.history.map((h) => (
          <li key={h.id}>
            <p className="font-semibold">
              {statusLabels[h.action] || displayLabel(h.action, 'Đã cập nhật lượt đặt')}
            </p>
            <p className="mt-2 text-xs text-slate-500">
              {new Date(h.createdAt).toLocaleString('vi-VN')}
            </p>
            <details className="mt-2 text-sm">
              <summary>Thông tin sự kiện</summary>
              <div className="mt-3 overflow-x-auto bg-slate-50 p-3 text-xs">
                <RecordDetails value={h.details} />
              </div>
            </details>
          </li>
        ))}
      </ol>
    </BannerPage>
  )
}
