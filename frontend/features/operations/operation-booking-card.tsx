'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { CourtLines } from '@/features/home/home-art'
import { QrUploadButton } from '@/features/operations/qr-checkin'
import type { useOperationalDetail } from '@/features/operations/use-operational-detail'
import { card, primaryPill } from '@/features/transfers/transfer-styles'
import { Clock, CreditCard, MapPin, Phone, UserRound } from 'lucide-react'

const actionBox =
  'mt-5 rounded-2xl border border-emerald-200/80 bg-emerald-50/70 p-4 dark:border-emerald-500/20 dark:bg-emerald-500/10'

export function OperationBookingCard({
  model,
}: {
  model: Pick<
    ReturnType<typeof useOperationalDetail>,
    | 'b'
    | 'guestLabel'
    | 'can'
    | 'token'
    | 'a'
    | 'performCheckIn'
    | 'handleQrDetected'
    | 'setToken'
    | 'canPay'
    | 'setPaymentOpen'
    | 'facility'
    | 'courts'
    | 'actorLabel'
  >
}) {
  const {
    b,
    guestLabel,
    can,
    token,
    a,
    performCheckIn,
    handleQrDetected,
    setToken,
    canPay,
    setPaymentOpen,
    facility,
    courts,
    actorLabel,
  } = model
  if (!b) return null
  const timeZone = b.priceSnapshot.timezone
  const start = new Date(b.startsAt)
  const part = (options: Intl.DateTimeFormatOptions) =>
    start.toLocaleDateString('vi-VN', { timeZone, ...options })
  const time = (value: string) =>
    new Date(value).toLocaleTimeString('vi-VN', { timeZone, hour: '2-digit', minute: '2-digit' })
  const court = courts.data?.find((c) => c.id === b.courtId)?.name
  const walkIn = b.source === 'OFFLINE_COUNTER' && b.currentHolderId === null
  return (
    <section className={`${card} home-rise p-5 sm:p-6`}>
      <CourtLines className="pointer-events-none absolute -right-20 -top-12 -z-10 w-80 -rotate-6 text-emerald-600/[0.05]" />
      <div className="flex flex-wrap items-start gap-x-5 gap-y-4">
        <div className="flex size-[72px] shrink-0 flex-col items-center justify-center rounded-2xl bg-emerald-700 text-white shadow-md">
          <span className="text-[11px] font-semibold uppercase">{part({ weekday: 'short' })}</span>
          <span className="text-2xl font-extrabold leading-none tabular-nums">
            {part({ day: '2-digit' })}
          </span>
          <span className="mt-0.5 text-[11px] font-medium tabular-nums">
            {start.toLocaleDateString('en-GB', { timeZone, month: '2-digit', year: 'numeric' })}
          </span>
        </div>
        <div className="min-w-0 flex-1 basis-56">
          <h2 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
            {facility.data?.name || 'Lượt đặt sân'}
            {court ? ` · ${court}` : ''}
          </h2>
          <p className="mt-1.5 flex items-center gap-1.5 text-sm font-semibold text-slate-700 tabular-nums dark:text-slate-300">
            <Clock className="size-3.5 shrink-0 text-emerald-600" />
            {time(b.startsAt)} – {time(b.endsAt)}
          </p>
          {facility.data && (
            <p className="mt-1 flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
              <MapPin className="size-3.5 shrink-0" />
              {facility.data.ward}, {facility.data.province}
            </p>
          )}
        </div>
      </div>

      <div className="mt-5 flex flex-wrap items-center gap-x-5 gap-y-2 rounded-2xl border border-slate-200/80 bg-slate-50/80 p-3.5 text-sm text-slate-700 dark:border-slate-800 dark:bg-slate-800/50 dark:text-slate-300">
        <span className="flex items-center gap-2 font-semibold">
          <UserRound className="size-4 shrink-0 text-emerald-600" />
          {guestLabel}
          {b.guestName ? ` · Khách lúc đặt: ${b.guestName}` : ''}
        </span>
        {b.guestPhone && (
          <span className="flex items-center gap-2 tabular-nums">
            <Phone className="size-4 shrink-0 text-emerald-600" />
            {b.guestPhone}
          </span>
        )}
      </div>
      <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">
        {b.currentHolderId && (
          <span title={b.currentHolderId}>
            Tài khoản sở hữu hiện tại: {actorLabel(b.currentHolderId)} ·{' '}
          </span>
        )}
        <span title={b.createdBy}>Người tạo lượt đặt: {actorLabel(b.createdBy)}</span>
      </p>

      {can('BOOKING_CHECK_IN') && b.status === 'CONFIRMED' && walkIn && (
        <div className={actionBox}>
          <p className="text-sm text-emerald-900 dark:text-emerald-200">
            Khách vãng lai không có mã vào sân. Xác nhận khi khách đã có mặt.
          </p>
          <Button
            type="button"
            disabled={a.busy}
            className={`${primaryPill} mt-3`}
            onClick={() => void a.run(() => performCheckIn(''))}
          >
            Xác nhận khách vãng lai đến sân
          </Button>
        </div>
      )}
      {can('BOOKING_CHECK_IN') && b.status === 'CONFIRMED' && !walkIn && (
        <form
          className={`${actionBox} space-y-3`}
          onSubmit={(e) => {
            e.preventDefault()
            const val = token || String(new FormData(e.currentTarget).get('token') || '')
            void a.run(() => performCheckIn(val))
          }}
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-sm font-semibold text-emerald-900 dark:text-emerald-200">
              Mã xác nhận vào sân 10 ký tự hoặc QR khách cung cấp
            </span>
            <QrUploadButton onTokenDetected={handleQrDetected} disabled={a.busy} />
          </div>
          <div className="flex flex-wrap gap-3">
            <Input
              name="token"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="Nhập mã hoặc bấm Tải ảnh QR ở trên"
              required
              disabled={a.busy}
              className="h-11 min-w-52 flex-1 rounded-xl"
            />
            <Button type="submit" disabled={a.busy} className={primaryPill}>
              Xác nhận vào sân lượt đặt
            </Button>
          </div>
        </form>
      )}
      {canPay && b.status === 'PENDING' && (
        <Button
          type="button"
          onClick={() => setPaymentOpen(true)}
          className={`${primaryPill} mt-5`}
        >
          <CreditCard className="size-4" />
          <span>Thanh toán tại quầy</span>
        </Button>
      )}
    </section>
  )
}
