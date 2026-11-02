'use client'
import { PageBanner } from '@/components/page-banner'
import { StaffGroupAttendance } from '@/features/groups/staff-attendance'
import { Status } from '@/features/integration/shared'
import { CounterPaymentModal } from '@/features/operations/counter-payment-modal'
import { OperationBookingCard } from '@/features/operations/operation-booking-card'
import { OperationHistory, OperationPrice } from '@/features/operations/operation-history'
import { useOperationalDetail } from '@/features/operations/use-operational-detail'
import { bannerLink, statePill } from '@/features/transfers/transfer-styles'
import { displayLabel } from '@/lib/display-labels'
import Link from 'next/link'
export function OperationalDetail({ id }: { id: string }) {
  const model = useOperationalDetail({ id })
  const { workspace, r, a, b, can, paymentOpen, setPaymentOpen } = model
  return (
    <>
      <PageBanner
        icon="shuttle"
        eyebrow={
          <Link href={`/${workspace || 'staff'}/bookings`} className={bannerLink}>
            Về lịch vận hành
          </Link>
        }
        title="Chi tiết vận hành lượt đặt"
        description={`Mã đặt ${id.slice(0, 8).toUpperCase()}`}
        actions={b && <span className={statePill}>{displayLabel(b.status)}</span>}
      />
      <div className="mx-auto max-w-6xl space-y-6 px-4 py-7 sm:px-6 sm:py-9">
        <Status loading={r.loading} error={r.error || a.error} />
        {r.data && b && (
          <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
            <div className="min-w-0 space-y-6">
              <OperationBookingCard model={model} />
              <StaffGroupAttendance
                id={id}
                allowed={can('BOOKING_CHECK_IN')}
                readable={can('BOOKING_READ')}
              />
              <OperationHistory model={model} />
            </div>
            <OperationPrice model={model} />
          </div>
        )}
      </div>
      {b && (
        <CounterPaymentModal
          booking={b}
          isOpen={paymentOpen}
          onClose={() => setPaymentOpen(false)}
          onSuccess={() => r.reload()}
        />
      )}
    </>
  )
}
