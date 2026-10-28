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
          className="rounded-full border px-4 py-2 text-sm text-emerald-700"
        >
          Ghi nhận có mặt khi tranh chấp
        </Link>
      )}
      {can('BOOKING_CHECK_IN') && (
        <Button
          type="button"
          onClick={() => setQuickQrOpen(true)}
          className="rounded-full bg-emerald-600 hover:bg-emerald-700 text-white shadow-xs flex items-center gap-2 px-4 py-2 text-sm font-semibold"
        >
          <QrCode className="h-4 w-4" />
          <span>Tải ảnh QR xác nhận vào sân</span>
        </Button>
      )}
      {can('BOOKING_CREATE_COUNTER') && facilityId && (
        <Link
          className="rounded-full border border-emerald-600 bg-white px-5 py-2 text-sm font-semibold text-emerald-700 hover:bg-emerald-50"
          href={`/${owner ? 'owner' : 'staff'}/counter-booking?facilityId=${facilityId}`}
        >
          Đặt tại quầy
        </Link>
      )}
    </div>
  )
}
