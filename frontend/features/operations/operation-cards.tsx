'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { canPayBooking } from '@/features/booking/ownership'
import { statusLabels } from '@/features/booking/types'
import { QrUploadButton } from '@/features/operations/qr-checkin'
import type { useOperationBookings } from '@/features/operations/use-operation-bookings'
import { money } from '@/features/schedule/types'
import { CreditCard } from 'lucide-react'
import Link from 'next/link'
export function OperationCards({
  model,
}: {
  model: Pick<
    ReturnType<typeof useOperationBookings>,
    | 'bookings'
    | 'filter'
    | 'user'
    | 'can'
    | 'owner'
    | 'cardTokens'
    | 'action'
    | 'setCardTokens'
    | 'busy'
    | 'setPaymentModalBooking'
  >
}) {
  const {
    bookings,
    filter,
    user,
    can,
    owner,
    cardTokens,
    action,
    setCardTokens,
    busy,
    setPaymentModalBooking,
  } = model
  return (
    <div className="space-y-4">
      {bookings.data?.items
        .filter((b) => !filter || b.status === filter)
        .map((b) => {
          const guestLabel =
            b.source === 'OFFLINE_COUNTER'
              ? b.currentHolderId
                ? 'Khách có tài khoản'
                : 'Khách vãng lai'
              : 'Khách trực tuyến'
          const canPay = canPayBooking(b, user, Boolean(can('BOOKING_CREATE_COUNTER')))
          return (
            <article
              key={b.id}
              className="rounded-xl border border-slate-200 bg-white p-4 shadow-2xs"
            >
              <div className="flex flex-wrap justify-between gap-3">
                <div>
                  <h2 className="text-sm font-semibold text-slate-900">
                    {new Date(b.startsAt).toLocaleString('vi-VN', {
                      timeZone: b.priceSnapshot.timezone,
                    })}{' '}
                    · {statusLabels[b.status]}
                  </h2>
                  <p className="mt-1.5 text-xs text-slate-500">
                    {guestLabel}
                    {b.guestName ? ` · Khách lúc đặt: ${b.guestName}` : ''} {b.guestPhone || ''} ·{' '}
                    <span className="font-mono">{b.id.slice(0, 8).toUpperCase()}</span>
                  </p>
                  <p className="mt-1 text-xs text-slate-400">Người tạo: {b.createdBy}</p>
                </div>
                <div className="text-right">
                  <p className="text-sm font-bold text-slate-900">{money(b.amount, b.currency)}</p>
                  <Link
                    className="mt-1.5 block text-xs font-medium text-emerald-700 underline underline-offset-4"
                    href={`/${owner ? 'owner' : 'staff'}/bookings/${b.id}`}
                  >
                    Chi tiết vận hành & điểm danh nhóm
                  </Link>
                </div>
              </div>
              {b.status === 'CONFIRMED' &&
                can('BOOKING_CHECK_IN') &&
                b.source === 'OFFLINE_COUNTER' &&
                b.currentHolderId === null && (
                  <Button
                    type="button"
                    className="mt-5"
                    disabled={busy}
                    onClick={() => action(b, 'check-in')}
                  >
                    Xác nhận khách vãng lai đến sân
                  </Button>
                )}
              {b.status === 'CONFIRMED' &&
                can('BOOKING_CHECK_IN') &&
                !(b.source === 'OFFLINE_COUNTER' && b.currentHolderId === null) && (
                  <form
                    className="mt-5 flex flex-wrap items-center gap-3"
                    onSubmit={(e) => {
                      e.preventDefault()
                      const val =
                        cardTokens[b.id] ?? String(new FormData(e.currentTarget).get('token') || '')
                      action(b, 'check-in', val)
                    }}
                  >
                    <div className="flex min-w-64 flex-1 items-center gap-2">
                      <Input
                        name="token"
                        value={cardTokens[b.id] ?? ''}
                        onChange={(e) => {
                          const v = e.target.value
                          setCardTokens((prev) => ({ ...prev, [b.id]: v }))
                        }}
                        aria-label={`Mã xác nhận vào sân ${b.id.slice(0, 8)}`}
                        placeholder="Mã 10 ký tự hoặc mã QR…"
                        className="flex-1"
                        required
                        disabled={busy}
                      />
                      <QrUploadButton
                        onTokenDetected={(token, bookingId) => {
                          if (bookingId && bookingId.toLowerCase() !== b.id.toLowerCase()) {
                            if (
                              window.confirm(
                                `Ảnh QR này thuộc lượt đặt #${bookingId.slice(0, 8).toUpperCase()} (khác với lượt đặt hiện tại #${b.id.slice(0, 8).toUpperCase()}). Bạn có muốn xác nhận vào sân cho lượt đặt #${bookingId.slice(0, 8).toUpperCase()} không?`,
                              )
                            ) {
                              action({ ...b, id: bookingId }, 'check-in', token)
                              return
                            }
                          }
                          setCardTokens((prev) => ({ ...prev, [b.id]: token }))
                          action(b, 'check-in', token)
                        }}
                        disabled={busy}
                      />
                    </div>
                    <Button type="submit" disabled={busy}>
                      Xác nhận vào sân
                    </Button>
                  </form>
                )}
              {b.status === 'PENDING' && canPay && (
                <Button
                  type="button"
                  onClick={() => setPaymentModalBooking(b)}
                  className="mt-4 gap-2 bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-xs px-4 py-2 rounded-lg shadow-xs flex items-center"
                >
                  <CreditCard className="size-4" />
                  <span>Thanh toán mô phỏng tại quầy</span>
                </Button>
              )}
            </article>
          )
        })}
    </div>
  )
}
