'use client'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { BannerPage } from '@/components/page-banner'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Court, Facility } from '@/features/facility/types'
import {
  type Hours,
  type Preview,
  type PriceRule,
  money,
  weekdays,
} from '@/features/schedule/types'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
export function StaffSchedule() {
  return (
    <RequireAuth roles={['STAFF']}>
      <Loaded />
    </RequireAuth>
  )
}
function Loaded() {
  const access = useApi<{ facilityBindings: { facilityId: string; permissions: string[] }[] }>(
    '/users/me/access',
  )
  const publicFacilities = useApi<Facility[]>('/facilities')
  const [selection, setSelection] = useState('')
  const [courtSelection, setCourtSelection] = useState('')
  const [date, setDate] = useState(new Date().toLocaleDateString('en-CA'))
  const facilities = publicFacilities.data?.filter((f) =>
    access.data?.facilityBindings.some(
      (b) =>
        b.facilityId === f.id &&
        b.permissions.some((p) =>
          [
            'SCHEDULE_READ',
            'SCHEDULE_MANAGE',
            'COURT_MAINTENANCE_MANAGE',
            'COURT_STATUS_MANAGE',
          ].includes(p),
        ),
    ),
  )
  const facility = selection || facilities?.[0]?.id
  const writes =
    access.data?.facilityBindings.find((b) => b.facilityId === facility)?.permissions ?? []
  const tool = writes.includes('SCHEDULE_MANAGE')
    ? 'schedule'
    : writes.includes('COURT_MAINTENANCE_MANAGE')
      ? 'maintenance'
      : writes.includes('COURT_STATUS_MANAGE')
        ? 'courts'
        : null
  const canRead = writes.includes('SCHEDULE_READ') || writes.includes('SCHEDULE_MANAGE')
  const courts = useApi<Court[]>(facility ? `/facilities/${facility}/courts` : null)
  const court = courtSelection || courts.data?.[0]?.id
  const hours = useApi<Hours[]>(
    canRead && facility ? `/schedules/facilities/${facility}/hours` : null,
  )
  const prices = useApi<PriceRule[]>(
    canRead && facility ? `/schedules/facilities/${facility}/pricing` : null,
  )
  const preview = useApi<Preview>(
    canRead && facility && court
      ? `/schedules/facilities/${facility}/preview?courtId=${court}&date=${date}`
      : null,
  )
  const error =
    access.error ||
    publicFacilities.error ||
    courts.error ||
    hours.error ||
    prices.error ||
    preview.error
  return (
    <BannerPage
      eyebrow={
        <Link className={bannerLink} href="/staff/bookings">
          Vận hành / Lịch và giá
        </Link>
      }
      title="Lịch sân và bảng giá"
      description="Lịch và giá chỉ đọc theo quyền được cấp tại cơ sở. Các thao tác cấu hình cần quyền chuyên biệt; preview không là lịch trống lượt đặt."
    >
      <div className="mt-4">
        {tool && facility && (
          <Link
            className="font-semibold text-emerald-700 hover:underline"
            href={`/staff/facilities/${facility}/${tool}`}
          >
            Cấu hình lịch, bảo trì và trạng thái theo quyền được cấp
          </Link>
        )}
      </div>
      <div className="my-6 flex flex-wrap gap-4">
        <label className="grid gap-2 text-sm">
          Cơ sở
          <select
            className="rounded-2xl border border-slate-200/90 p-3"
            value={facility || ''}
            onChange={(e) => {
              setSelection(e.target.value)
              setCourtSelection('')
            }}
          >
            {facilities?.map((f) => (
              <option key={f.id} value={f.id}>
                {f.name}
              </option>
            ))}
          </select>
        </label>
        <label className="grid gap-2 text-sm">
          Sân
          <select
            className="rounded-2xl border border-slate-200/90 p-3"
            value={court || ''}
            onChange={(e) => setCourtSelection(e.target.value)}
          >
            {courts.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label className="grid gap-2 text-sm">
          Ngày
          <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
      </div>
      {error && (
        <p role="alert" className="mb-5 text-red-700">
          {error}
        </p>
      )}
      {(access.loading || publicFacilities.loading || preview.loading) && (
        <p role="status">Đang tải lịch…</p>
      )}
      {facilities?.length === 0 && <p>Chưa được cấp quyền xem lịch tại cơ sở nào.</p>}
      <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-4">
        {preview.data?.slots.map((s) => (
          <div
            key={s.startsAt}
            className={`rounded-2xl border border-slate-200/90 p-4 ${s.state === 'ELIGIBLE' ? 'border-emerald-200 bg-emerald-50' : 'bg-slate-100 text-slate-500'}`}
          >
            <p className="font-semibold">
              {new Date(s.startsAt).toLocaleTimeString('vi-VN', {
                timeZone: preview.data?.timezone,
                hour: '2-digit',
                minute: '2-digit',
              })}
              –
              {new Date(s.endsAt).toLocaleTimeString('vi-VN', {
                timeZone: preview.data?.timezone,
                hour: '2-digit',
                minute: '2-digit',
              })}
            </p>
            <p className="mt-2 text-sm">{s.amount == null ? 'Chưa có giá' : money(s.amount)}</p>
            <p className="mt-2 text-xs">
              {s.reason ? displayLabel(s.reason, 'Không thể đặt') : s.ruleLabel || 'Mở cửa'}
            </p>
          </div>
        ))}
      </div>
      <div className="mt-8 grid gap-6 lg:grid-cols-2">
        <section className="p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          <h2 className="text-lg font-extrabold tracking-tight">Giờ hoạt động</h2>
          <div className="mt-4 space-y-3">
            {hours.data
              ?.filter((h) => h.courtId == null || h.courtId === court)
              .map((h) => (
                <p className="text-sm" key={h.id}>
                  {weekdays[h.dayOfWeek - 1]} · {h.opensAt}–{h.closesAt} · {h.slotMinutes} phút ·{' '}
                  {h.courtId ? 'Theo sân' : 'Theo cơ sở'}
                </p>
              ))}
          </div>
        </section>
        <section className="p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          <h2 className="text-lg font-extrabold tracking-tight">Quy tắc giá hiện hành</h2>
          <div className="mt-4 space-y-4">
            {prices.data
              ?.filter((p) => p.active && (p.courtId == null || p.courtId === court))
              .map((p) => (
                <div key={p.id} className="text-sm">
                  <p className="font-semibold">
                    {p.label} · {money(p.pricePerSlot)}
                  </p>
                  <p className="mt-1 text-slate-500">
                    {p.specificDate || weekdays[(p.dayOfWeek || 1) - 1]} · {p.startsAt}–{p.endsAt} ·
                    Hiệu lực {p.effectiveFrom} → {p.effectiveTo || 'Không giới hạn'}
                  </p>
                </div>
              ))}
          </div>
        </section>
      </div>
    </BannerPage>
  )
}
