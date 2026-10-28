'use client'
import { PageBanner, bannerPrimary } from '@/components/page-banner'
import { displayLabel } from '@/lib/display-labels'
import Link from 'next/link'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { money } from '@/features/schedule/types'
import { type Booking, statusLabels } from './types'
export function MyBookings() {
  return (
    <RequireAuth>
      <Loaded />
    </RequireAuth>
  )
}

function Loaded() {
  const resource = useApi<Booking[]>('/bookings')
  return (
    <>
      <PageBanner
        eyebrow="Lịch chơi của bạn"
        title="Lịch đặt của tôi"
        description="Theo dõi trạng thái thanh toán, mã vào sân và chuyển nhượng lượt đặt."
        icon="tennis"
        actions={
          <Link className={bannerPrimary} href="/search">
            Đặt sân mới
          </Link>
        }
      />
      <div className="mx-auto max-w-5xl px-4 sm:px-6 py-6 sm:py-8">
        {resource.loading && (
          <p className="mt-6 text-xs text-slate-500" role="status">
            Đang tải lịch đặt…
          </p>
        )}
        {resource.error && (
          <p role="alert" className="mt-6 text-xs text-red-700">
            {resource.error}
            <button className="ml-3 font-semibold underline" onClick={resource.reload}>
              Thử lại
            </button>
          </p>
        )}
        {resource.data?.length === 0 && (
          <div className="my-6 rounded-xl border border-slate-200 bg-white p-6">
            <h2 className="text-base font-semibold text-slate-900">Chưa có lịch đặt</h2>
            <p className="mt-1.5 text-xs text-slate-500">
              Chọn một sân để bắt đầu buổi chơi tiếp theo.
            </p>
          </div>
        )}
        <div className="mt-5 grid gap-3.5">
          {resource.data?.map((b) => (
            <Link
              href={`/customer/bookings/${b.id}`}
              className="flex flex-wrap items-center justify-between gap-4 rounded-xl border border-slate-200 bg-white p-4 shadow-2xs transition hover:border-slate-300 hover:shadow-sm"
              key={b.id}
            >
              <div>
                <p className="text-sm font-semibold text-slate-900">
                  {new Date(b.startsAt).toLocaleString('vi-VN', {
                    timeZone: b.priceSnapshot.timezone,
                  })}
                </p>
                <p className="mt-1 text-xs text-slate-500">
                  Mã đặt: <span className="font-mono">{b.id.slice(0, 8).toUpperCase()}</span> ·{' '}
                  {b.source === 'OFFLINE_COUNTER' ? 'Tại quầy' : 'Trực tuyến'}
                </p>
              </div>
              <div className="text-right">
                <span className="inline-flex rounded-md border border-emerald-200 bg-emerald-50 px-2.5 py-0.5 text-[11px] font-semibold text-emerald-800">
                  {statusLabels[b.status] || displayLabel(b.status)}
                </span>
                <p className="mt-2 text-sm sm:text-base font-bold text-slate-900">
                  {money(b.amount, b.currency)}
                </p>
              </div>
            </Link>
          ))}
        </div>
      </div>
    </>
  )
}
