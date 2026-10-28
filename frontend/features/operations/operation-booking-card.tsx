'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card } from '@/features/integration/shared'
import { QrUploadButton } from '@/features/operations/qr-checkin'
import type { useOperationalDetail } from '@/features/operations/use-operational-detail'
import { displayLabel } from '@/lib/display-labels'
import { CreditCard } from 'lucide-react'
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
  } = model
  if (!b) return null
  return (
    <Card title={displayLabel(b.status)}>
      <p>{new Date(b.startsAt).toLocaleString('vi-VN', { timeZone: b.priceSnapshot.timezone })}</p>
      <p>
        {guestLabel}
        {b.guestName ? ` · Khách lúc đặt: ${b.guestName}` : ''} {b.guestPhone || ''}
      </p>
      {b.currentHolderId && (
        <p className="text-xs text-slate-500">Tài khoản sở hữu hiện tại: {b.currentHolderId}</p>
      )}
      <p className="text-xs text-slate-500">Người tạo lượt đặt: {b.createdBy}</p>
      {can('BOOKING_CHECK_IN') &&
        b.status === 'CONFIRMED' &&
        b.source === 'OFFLINE_COUNTER' &&
        b.currentHolderId === null && (
          <Button
            type="button"
            disabled={a.busy}
            onClick={() => void a.run(() => performCheckIn(''))}
          >
            Xác nhận khách vãng lai đến sân
          </Button>
        )}
      {can('BOOKING_CHECK_IN') &&
        b.status === 'CONFIRMED' &&
        !(b.source === 'OFFLINE_COUNTER' && b.currentHolderId === null) && (
          <form
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault()
              const val = token || String(new FormData(e.currentTarget).get('token') || '')
              void a.run(() => performCheckIn(val))
            }}
          >
            <div className="flex flex-wrap items-end gap-3">
              <div className="grid flex-1 gap-2 text-sm">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-slate-700">
                    Mã xác nhận vào sân 10 ký tự hoặc QR khách cung cấp
                  </span>
                  <QrUploadButton onTokenDetected={handleQrDetected} disabled={a.busy} />
                </div>
                <Input
                  name="token"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  placeholder="Nhập mã xác nhận vào sân 10 ký tự hoặc bấm Tải ảnh QR ở trên"
                  required
                  disabled={a.busy}
                />
              </div>
              <Button type="submit" disabled={a.busy} className="self-end">
                Xác nhận vào sân lượt đặt
              </Button>
            </div>
          </form>
        )}
      {canPay && b.status === 'PENDING' && (
        <Button
          type="button"
          onClick={() => setPaymentOpen(true)}
          className="gap-2 bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-xs px-4 py-2 rounded-lg flex items-center mt-3"
        >
          <CreditCard className="size-4" />
          <span>Thanh toán mô phỏng tại quầy</span>
        </Button>
      )}
    </Card>
  )
}
