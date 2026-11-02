'use client'

import React from 'react'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { ArrowLeft, Ticket, CheckCircle2 } from 'lucide-react'
import { useApi } from '@/lib/use-api'
import { useAuth } from '@/lib/auth-context'
import type { Facility } from '@/features/facility/types'
import type { Booking } from '@/features/booking/types'
import { PageBanner } from '@/components/page-banner'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { DirectionsCard } from './directions-card'

export function FacilityDirectionsPage({
  facilityId,
  initialBookingId,
}: {
  facilityId: string
  initialBookingId?: string
}) {
  const searchParams = useSearchParams()
  const paramBookingId = initialBookingId || searchParams.get('bookingId') || undefined

  const { user } = useAuth()
  const bookingsRes = useApi<Booking[]>(user ? '/bookings' : null)

  // Find confirmed or active booking for this facility from user's history
  const matchingBooking =
    bookingsRes.data?.find(
      (b) => b.facilityId === facilityId && (b.status === 'CONFIRMED' || b.status === 'CHECKED_IN'),
    ) || bookingsRes.data?.find((b) => b.facilityId === facilityId)

  const effectiveBookingId = paramBookingId || matchingBooking?.id

  const resource = useApi<Facility>(`/facilities/${facilityId}`)

  if (resource.loading) {
    return (
      <div className="mx-auto max-w-5xl px-4 py-16 text-center">
        <div className="inline-block h-8 w-8 animate-spin rounded-full border-2 border-slate-300 border-t-emerald-600" />
        <p className="mt-3 text-sm text-slate-500">Đang chuẩn bị bản đồ và thông tin cơ sở…</p>
      </div>
    )
  }

  const f = resource.data
  const venueName = f?.name || 'Cơ sở thể thao'
  const addressLine = f ? f.addressLine : 'Số 18 Phố Duy Tân'
  const ward = f ? f.ward : 'Phường Dịch Vọng Hậu'
  const province = f ? f.province : 'Hà Nội'
  const latitude = f ? f.latitude : 21.031345
  const longitude = f ? f.longitude : 105.783421

  const amenities =
    f?.amenities && f.amenities.length > 0
      ? f.amenities
      : ['🏸 Sân Chuẩn Thi Đấu', '🅿️ Bãi Đỗ Ô Tô & Xe Máy', '❄️ Điều Hòa & Phòng Nghỉ VIP']

  return (
    <div className="pb-20">
      <PageBanner
        eyebrow={
          effectiveBookingId ? (
            <Link href={`/customer/bookings/${effectiveBookingId}`} className={bannerLink}>
              Lượt đặt {effectiveBookingId.slice(0, 8).toUpperCase()}
            </Link>
          ) : (
            <Link href={`/facilities/${facilityId}`} className={bannerLink}>
              Chi tiết cơ sở
            </Link>
          )
        }
        title={venueName}
        description={`Chỉ đường đến ${addressLine}, ${ward}, ${province}`}
      />
      <div className="mx-auto max-w-5xl px-4 pt-3 sm:px-6 sm:pt-5">
        {/* Active Booking Banner if already booked/confirmed */}
        {effectiveBookingId && (
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-emerald-50 border border-emerald-200/90 p-4 text-sm text-emerald-900 shadow-xs">
            <div className="flex items-center gap-2.5">
              <CheckCircle2 className="h-5 w-5 text-emerald-600 shrink-0" />
              <div>
                <p className="font-semibold text-emerald-950">
                  Lượt đặt của bạn đã thanh toán thành công
                </p>
                <p className="text-xs text-emerald-800">
                  Mã lượt đặt:{' '}
                  <strong className="font-mono">
                    {effectiveBookingId.slice(0, 8).toUpperCase()}
                  </strong>{' '}
                  · Trạng thái: Đã xác nhận
                </p>
              </div>
            </div>
            <Link
              href={`/customer/bookings/${effectiveBookingId}`}
              className="inline-flex items-center gap-1.5 rounded-full bg-emerald-600 px-4 py-2 text-xs sm:text-sm font-semibold text-white shadow-xs hover:bg-emerald-700 transition"
            >
              <Ticket className="h-4 w-4" />
              <span>Xem vé xác nhận & QR xác nhận vào sân</span>
            </Link>
          </div>
        )}

        {/* Venue Info Top Card */}
        <div className="mt-5 rounded-3xl border border-slate-200/90 bg-white p-6 shadow-xl shadow-slate-900/5 home-rise sm:p-8">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="text-xs font-bold tracking-widest text-slate-500 uppercase">
              CƠ SỞ THỂ THAO
            </span>
            <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-800 ring-1 ring-emerald-200">
              <span className="h-2 w-2 rounded-full bg-emerald-500 animate-pulse" />
              Mở cửa 06:00 - 23:00
            </span>
          </div>

          {/* Amenities pill tags */}
          <div className="mt-5 flex flex-wrap gap-2 pt-2 border-t border-slate-100">
            {amenities.map((item, idx) => (
              <span
                key={idx}
                className="inline-flex items-center gap-1.5 rounded-full bg-slate-100/80 px-3.5 py-1.5 text-xs font-medium text-slate-700"
              >
                {item}
              </span>
            ))}
          </div>
        </div>

        {/* 🧭 The Main Directions & Address Card */}
        <div className="mt-6">
          <DirectionsCard
            venueName={venueName}
            addressLine={addressLine}
            ward={ward}
            province={province}
            latitude={latitude}
            longitude={longitude}
          />
        </div>

        {/* Bottom Navigation Links */}
        <div className="mt-8 flex items-center justify-end">
          <Link
            href={`/facilities/${facilityId}`}
            className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-5 py-2.5 text-sm font-semibold text-slate-600 shadow-xs transition hover:bg-slate-50 hover:text-slate-900"
          >
            <ArrowLeft className="h-4 w-4" />
            <span>
              {effectiveBookingId ? 'Xem chi tiết cơ sở' : 'Quay lại trang chi tiết cơ sở'}
            </span>
          </Link>
        </div>
      </div>
    </div>
  )
}
