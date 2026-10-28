'use client'
import { Button } from '@/components/ui/button'
import type { useOperationBookings } from '@/features/operations/use-operation-bookings'
import { QrCode } from 'lucide-react'
import Link from 'next/link'
export function OperationToolbar({
  model,
}: {
  model: Pick<
    ReturnType<typeof useOperationBookings>,
    'can' | 'setQuickQrOpen' | 'owner' | 'facilityId'
  >
}) {
  const { can, setQuickQrOpen, owner, facilityId } = model
  return (
    <div className="flex flex-wrap items-center gap-2">
      {!owner && facilityId && can('BOOKING_CHECK_IN') && (
        <Link
          href={`/staff/peer-presence?facilityId=${facilityId}`}
          className="inline-flex h-8 items-center rounded-lg border border-slate-300 bg-white px-3.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
        >
          Ghi nhận có mặt khi tranh chấp
        </Link>
      )}
      {can('BOOKING_CHECK_IN') && (
        <Button
          type="button"
          size="sm"
          onClick={() => setQuickQrOpen(true)}
          className="bg-emerald-700 hover:bg-emerald-800 text-white shadow-2xs flex items-center gap-1.5"
        >
          <QrCode className="size-3.5" strokeWidth={1.75} />
          <span>Tải ảnh QR xác nhận vào sân</span>
        </Button>
      )}
      {can('BOOKING_CREATE_COUNTER') && facilityId && (
        <Link
          className="inline-flex h-8 items-center rounded-lg border border-emerald-700 bg-white px-3.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50"
          href={`/${owner ? 'owner' : 'staff'}/counter-booking?facilityId=${facilityId}`}
        >
          Đặt tại quầy
        </Link>
      )}
    </div>
  )
}
