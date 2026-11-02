'use client'
import { BannerPage } from '@/components/page-banner'
import { Button } from '@/components/ui/button'
import { CounterPaymentModal } from '@/features/operations/counter-payment-modal'
import { OperationCards } from '@/features/operations/operation-cards'
import { OperationFilters } from '@/features/operations/operation-filters'
import { OperationToolbar } from '@/features/operations/operation-toolbar'
import { QuickQrCheckinModal } from '@/features/operations/qr-checkin'
import { useOperationBookings } from '@/features/operations/use-operation-bookings'
export function Loaded({ owner }: { owner: boolean }) {
  const model = useOperationBookings({ owner })
  const {
    owned,
    access,
    publicFacilities,
    bookings,
    error,
    message,
    facilities,
    filter,
    page,
    setPage,
    quickQrOpen,
    setQuickQrOpen,
    paymentModalBooking,
    setPaymentModalBooking,
    setMessage,
  } = model
  return (
    <BannerPage
      eyebrow="Vận hành tại sân"
      title={owner ? 'Vận hành lịch đặt' : 'Kiểm tra lượt đặt'}
      description="Tra cứu lượt đặt, xác nhận khách vào sân và thu tiền tại quầy."
    >
      <div className="mb-6 flex justify-end">
        <OperationToolbar model={model} />
      </div>
      <OperationFilters model={model} />
      {(owned.loading || access.loading || publicFacilities.loading || bookings.loading) && (
        <p role="status">Đang tải lịch đặt…</p>
      )}
      {(error || owned.error || access.error || publicFacilities.error || bookings.error) && (
        <p role="alert" className="my-4 text-red-700">
          {error || owned.error || access.error || publicFacilities.error || bookings.error}
        </p>
      )}
      {message && (
        <p role="status" className="my-4 text-emerald-700">
          {message}
        </p>
      )}
      {facilities?.length === 0 && (
        <p className="text-slate-500">
          Chưa có cơ sở trong phạm vi quản lý với quyền xem lượt đặt.
        </p>
      )}
      {bookings.data?.items.filter((b) => !filter || b.status === filter).length === 0 && (
        <p className="p-6 text-slate-500 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          Chưa có lượt đặt phù hợp.
        </p>
      )}
      <OperationCards model={model} />
      <div className="mt-5 flex items-center gap-3">
        <Button
          variant="outline"
          disabled={!page || bookings.loading}
          onClick={() => setPage(page - 1)}
        >
          Trang trước
        </Button>
        <span className="text-sm">
          {bookings.data?.total ?? 0} lượt đặt · Trang {page + 1}
        </span>
        <Button
          variant="outline"
          disabled={bookings.loading || !bookings.data || page + 1 >= bookings.data.totalPages}
          onClick={() => setPage(page + 1)}
        >
          Trang sau
        </Button>
      </div>
      <QuickQrCheckinModal
        isOpen={quickQrOpen}
        onClose={() => setQuickQrOpen(false)}
        onSuccess={() => bookings.reload()}
      />
      <CounterPaymentModal
        booking={paymentModalBooking}
        isOpen={Boolean(paymentModalBooking)}
        onClose={() => setPaymentModalBooking(null)}
        onSuccess={(msg) => {
          setMessage(msg)
          bookings.reload()
        }}
      />
    </BannerPage>
  )
}
