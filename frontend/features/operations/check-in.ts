import type { Booking } from '@/features/booking/types'
import { api } from '@/lib/api'

export function checkInBooking(
  booking: Pick<Booking, 'id' | 'facilityId'> &
    Partial<Pick<Booking, 'source' | 'currentHolderId'>>,
  raw: string,
): Promise<Booking> {
  const value = raw.trim()
  if (booking.source === 'OFFLINE_COUNTER' && booking.currentHolderId === null && !value) {
    return api<Booking>(`/bookings/${booking.id}/check-in-guest`, { method: 'POST' })
  }
  if (/^[0-9a-f]{10}$/i.test(value)) {
    return api<Booking>(`/bookings/facility/${booking.facilityId}/check-in-by-code`, {
      method: 'POST',
      body: JSON.stringify({ bookingCode: value.toUpperCase() }),
    })
  }
  if (!/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value)) {
    throw new Error('Nhập mã xác nhận vào sân gồm 10 ký tự hoặc mã QR hợp lệ.')
  }
  return api<Booking>(`/bookings/${booking.id}/check-in`, {
    method: 'POST',
    body: JSON.stringify({ token: value }),
  })
}
