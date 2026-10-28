'use client'
import { timezoneLabel } from '@/lib/display-labels'
import Image from 'next/image'
import Link from 'next/link'
import { MapPin, Navigation, Ticket } from 'lucide-react'
import { useApi } from '@/lib/use-api'
import { useAuth } from '@/lib/auth-context'
import { FavoriteButton } from '@/features/integration/favorites'
import type { Facility, Court, Media, Category } from '@/features/facility/types'
import type { Booking } from '@/features/booking/types'
import { DirectionsCard } from './directions-card'

export function PublicFacilityDetail({ id }: { id: string }) {
  const resource = useApi<Facility>(`/facilities/${id}`)
  const courts = useApi<Court[]>(`/facilities/${id}/courts?includeDisabled=true`)
  const images = useApi<Media[]>(`/facilities/${id}/images`)
  const categories = useApi<Category[]>('/sport-categories')

  const { user } = useAuth()
  const bookingsRes = useApi<Booking[]>(user ? '/bookings' : null)
  const confirmedBooking =
    bookingsRes.data?.find(
      (b) => b.facilityId === id && (b.status === 'CONFIRMED' || b.status === 'CHECKED_IN'),
    ) || bookingsRes.data?.find((b) => b.facilityId === id)

  if (resource.loading)
    return (
      <p className="p-8" role="status">
        Đang tải cơ sở…
      </p>
    )
  if (resource.error || !resource.data)
    return (
      <div className="p-8" role="alert">
        {resource.error}
        <Link className="ml-4 text-emerald-700 underline" href="/search">
          Quay lại tìm sân
        </Link>
      </div>
    )

  const f = resource.data
  return (
    <div className="mx-auto max-w-6xl px-5 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-slate-500">
        <Link href="/search" className="hover:underline">
          Tìm sân / {f.name}
        </Link>
        <Link
          href={`/facilities/${id}/directions${confirmedBooking ? `?bookingId=${confirmedBooking.id}` : ''}`}
          className="inline-flex items-center gap-1.5 font-semibold text-rose-700 hover:text-rose-800 hover:underline"
        >
          <Navigation className="h-4 w-4" />
          <span>Chỉ đường & Vị trí trực tiếp</span>
        </Link>
      </div>

      {confirmedBooking && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-4 rounded-xl border border-emerald-200 bg-emerald-50/80 p-4 shadow-2xs">
          <div className="flex items-center gap-3">
            <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-emerald-700 text-xs text-white font-bold">
              ✓
            </div>
            <div>
              <p className="text-sm font-semibold text-emerald-950">
                Bạn đã thanh toán và có lịch đặt tại cơ sở này
              </p>
              <p className="text-xs text-emerald-800">
                Mã lượt đặt:{' '}
                <strong className="font-mono">
                  {confirmedBooking.id.slice(0, 8).toUpperCase()}
                </strong>{' '}
                · Trạng thái: Đã xác nhận
              </p>
            </div>
          </div>
          <Link
            href={`/customer/bookings/${confirmedBooking.id}`}
            className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-emerald-700 px-3.5 text-xs font-semibold text-white shadow-2xs transition hover:bg-emerald-800"
          >
            <Ticket className="size-3.5" strokeWidth={1.75} />
            <span>Quay lại phần xác nhận</span>
          </Link>
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-5">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold tracking-tight text-slate-900">{f.name}</h1>
          <div className="mt-1.5 flex items-center gap-1.5 text-slate-600">
            <MapPin className="size-3.5 shrink-0 text-emerald-700" strokeWidth={1.75} />
            <p className="text-xs sm:text-sm">
              {f.addressLine}, {f.ward}, {f.province}
            </p>
          </div>
        </div>
        <div>
          <FavoriteButton id={id} />
        </div>
      </div>

      {images.data && images.data.length > 0 && (
        <div className="mt-5 grid gap-3 sm:grid-cols-3">
          {images.data.map((i) => (
            <Image
              unoptimized
              src={i.url}
              alt={`Ảnh ${f.name}`}
              key={i.id}
              width={480}
              height={320}
              className="h-44 w-full rounded-xl border border-slate-200 object-cover"
            />
          ))}
        </div>
      )}
      {images.error && (
        <p role="alert" className="mt-4 text-xs text-red-700">
          Không tải được ảnh: {images.error}
        </p>
      )}

      <div className="my-6 grid gap-6 md:grid-cols-[1fr_260px]">
        <div>
          <h2 className="text-base font-bold text-slate-900">Về cơ sở</h2>
          <p className="mt-2 text-xs sm:text-sm leading-relaxed text-slate-600">
            {f.description || 'Cơ sở chưa bổ sung mô tả.'}
          </p>
          <div className="mt-4 flex flex-wrap gap-1.5">
            {f.amenities.map((a) => (
              <span
                key={a}
                className="rounded-md border border-slate-200 bg-slate-50 px-2.5 py-1 text-xs font-medium text-slate-700"
              >
                {a}
              </span>
            ))}
          </div>
        </div>
        <aside className="rounded-xl border border-slate-200 bg-slate-50 p-4">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
            Thông tin liên hệ
          </h3>
          <p className="mt-2 text-sm font-semibold text-slate-900">{f.phone}</p>
          <p className="mt-1.5 text-xs text-slate-500">
            Lịch và giờ chơi theo {timezoneLabel(f.timezone)}.
          </p>
        </aside>
      </div>

      <div className="my-8">
        <DirectionsCard
          venueName={f.name}
          addressLine={f.addressLine}
          ward={f.ward}
          province={f.province}
          latitude={f.latitude}
          longitude={f.longitude}
        />
      </div>

      <h2 className="text-lg font-bold text-slate-900">Chọn sân</h2>
      {courts.loading && (
        <p role="status" className="mt-4 text-xs text-slate-500">
          Đang tải sân…
        </p>
      )}
      {courts.error && (
        <p role="alert" className="mt-4 text-xs text-red-700">
          {courts.error}
        </p>
      )}
      {courts.data?.length === 0 && (
        <p className="mt-4 text-xs text-slate-500">Hiện chưa có sân đang nhận đặt chỗ.</p>
      )}
      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        {courts.data?.map((c) => (
          <article
            className="flex flex-col justify-between rounded-xl border border-slate-200 bg-white p-4 shadow-2xs"
            key={c.id}
          >
            <div>
              <div className="flex items-center justify-between gap-2">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-emerald-700">
                  {categories.data?.find((v) => v.id === c.sportCategoryId)?.name || 'Sân thể thao'}
                </p>
              </div>
              <h3 className="mt-1.5 text-base font-semibold text-slate-900">{c.name}</h3>
              <p className="mt-1 text-xs text-slate-500">{c.description || c.code}</p>
              {!c.enabled && (
                <p className="mt-3 text-xs font-medium text-amber-800">Sân tạm ngừng nhận đặt.</p>
              )}
            </div>
            {c.enabled && (
              <div className="mt-4 flex items-center justify-between gap-3 border-t border-slate-100 pt-3">
                <Link
                  href={`/facilities/${id}/courts/${c.id}`}
                  className="inline-flex h-8 items-center rounded-lg bg-emerald-700 px-4 text-xs font-semibold text-white hover:bg-emerald-800 transition-colors"
                >
                  Kiểm tra lịch trống
                </Link>
              </div>
            )}
          </article>
        ))}
      </div>
    </div>
  )
}
