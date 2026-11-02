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
import { translateError } from '@/lib/error-translator'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
import { AdminPage } from './admin-nav'
export function BookingMonitor() {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Loaded />
    </RequireAuth>
  )
}
function Loaded() {
  const [path, setPath] = useState('/admin/bookings')
  const r = useApi<Booking[]>(path)
  function search(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = new FormData(e.currentTarget),
      p = new URLSearchParams()
    for (const key of ['id', 'facilityId', 'status']) {
      const v = String(f.get(key) || '')
      if (v) p.set(key, v)
    }
    setPath(`/admin/bookings?${p}`)
  }
  return (
    <AdminPage active="bookings" title="Giám sát lượt đặt">
      <form
        onSubmit={search}
        className="mt-6 grid gap-4 p-5 md:grid-cols-4 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
      >
        <label className="grid gap-2 text-sm">
          Mã lượt đặt
          <Input name="id" placeholder="Mã định danh lượt đặt" />
        </label>
        <label className="grid gap-2 text-sm">
          Mã cơ sở
          <Input name="facilityId" placeholder="Mã định danh cơ sở" />
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
        <p className="mt-6 p-6 text-slate-500 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          Không có lượt đặt phù hợp.
        </p>
      )}
      <div className="mt-6 divide-y rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
        {r.data?.map((b) => (
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
              <p className="mt-2 text-xs text-slate-400">Cơ sở {b.facilityId}</p>
            </div>
            <strong className="text-emerald-700">{money(b.amount, b.currency)}</strong>
          </Link>
        ))}
      </div>
      <Link
        href="/admin/bookings/reconciliation"
        className="mt-6 inline-block text-sm font-semibold text-emerald-700 hover:underline"
      >
        Mở danh sách cần đối soát
      </Link>
    </AdminPage>
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
export function BookingReconciliation() {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Reconciliation />
    </RequireAuth>
  )
}
function Reconciliation() {
  const r = useApi<
    {
      id: string
      booking_id: string
      payment_id: string
      reason: string
      state: string
      created_at: string
    }[]
  >('/admin/bookings/reconciliation')
  return (
    <AdminPage
      active="bookings/reconciliation"
      title="Thanh toán cần đối soát"
      description="Các kết quả không đủ điều kiện xác nhận được giữ lại để kiểm tra. Chính sách hoàn tiền chưa được chốt."
      width="max-w-5xl"
    >
      {r.loading && (
        <p className="mt-6" role="status">
          Đang tải đối soát…
        </p>
      )}
      {r.error && (
        <p className="mt-6 text-red-700" role="alert">
          {r.error}
        </p>
      )}
      {r.data?.length === 0 && (
        <p className="mt-6 p-6 text-slate-500 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          Chưa có trường hợp cần đối soát.
        </p>
      )}
      <div className="mt-6 space-y-4">
        {r.data?.map((x) => (
          <article
            key={x.id}
            className="p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
          >
            <p className="font-semibold text-amber-800">
              {translateError(x.reason, { preserveUnknown: true })} · {displayLabel(x.state)}
            </p>
            <div className="mt-3 flex gap-5 text-sm text-emerald-700">
              <Link href={`/admin/bookings/reconciliation/${x.id}`}>Rà soát và ghi chú</Link>
              <Link href={`/admin/bookings/${x.booking_id}`}>Xem lượt đặt</Link>
              <Link href={`/admin/payments/${x.payment_id}`}>Xem giao dịch</Link>
            </div>
            <p className="mt-3 text-xs text-slate-500">
              {new Date(x.created_at).toLocaleString('vi-VN')}
            </p>
          </article>
        ))}
      </div>
    </AdminPage>
  )
}
