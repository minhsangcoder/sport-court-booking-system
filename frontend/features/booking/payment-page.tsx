'use client'
import { displayLabel } from '@/lib/display-labels'
import Link from 'next/link'
import { useState } from 'react'
import { usePolling } from '@/lib/use-polling'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useAuth } from '@/lib/auth-context'
import { RequireAuth } from '@/features/auth/require-auth'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import type { Payment, BookingDetail } from './types'
import { PaymentSynchronization } from './payment-synchronization'
import { Countdown } from './countdown'
import { bookingResultPath } from './ownership'
import { RefundPolicyNotice } from './refund-policy-notice'
import { notifyDemoMoneyChanged } from './demo-finance'
import { TransferFinanceNotice } from '@/features/transfers/finance-notice'
import type { Acquisition } from '@/features/transfers/types'
import { CashPaymentResult } from '@/features/payments/counter-cash-payment'
import { BankPaymentInstructions } from '@/features/payments/bank-payment-instructions'
import type { PaymentRuntime } from '@/features/payments/types'
export function PaymentPage({ id }: { id: string }) {
  return (
    <RequireAuth>
      <Loaded id={id} />
    </RequireAuth>
  )
}
function Loaded({ id }: { id: string }) {
  const router = useRouter()
  const { user } = useAuth()
  const resource = useApi<Payment>(`/payments/${id}`)
  const runtime = useApi<PaymentRuntime>('/payments/runtime')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [confirmation, setConfirmation] = useState<{ payment: Payment; deadline: number } | null>(
    null,
  )
  const waiting = Boolean(confirmation)
  const bookingResource = useApi<BookingDetail>(
    resource.data?.bookingId && resource.data.purpose !== 'TRANSFER'
      ? `/bookings/${resource.data.bookingId}`
      : null,
  )
  const acquisitionResource = useApi<Acquisition>(
    resource.data?.acquisitionId ? `/transfers/acquisitions/${resource.data.acquisitionId}` : null,
  )
  const owned = useApi<{ id: string }[]>(user?.roles.includes('OWNER') ? '/owner/facilities' : null)

  usePolling(
    async (signal) => {
      if (!confirmation) return false
      try {
        const p = confirmation.payment
        const detail = await api<BookingDetail>(`/bookings/${p.bookingId}`, { signal })
        if (signal.aborted) return
        const target = bookingResultPath(
          p,
          detail.booking,
          user,
          owned.data?.map((f) => f.id),
        )
        if (detail.booking.status === 'CONFIRMED' && target) {
          setConfirmation(null)
          router.push(target)
          return false
        }
      } catch (err) {
        if (!signal.aborted) {
          setConfirmation(null)
          setError(err instanceof Error ? err.message : 'Không thể tải trạng thái.')
          return false
        }
      }
    },
    {
      enabled: waiting,
      scope: id,
      interval: 1000,
      maxAttempts: 15,
      deadline: confirmation?.deadline,
      onTimeout: () => {
        setConfirmation(null)
        setError(
          'Thanh toán đã nhận, đang đối soát trạng thái lượt đặt. Mở lịch đặt hoặc thử tải lại.',
        )
      },
    },
  )

  async function complete(outcome: 'SUCCESS' | 'FAILED') {
    if (busy || waiting) return
    setBusy(true)
    setError('')
    try {
      const p = await api<Payment>(`/payments/${id}/demo/complete`, {
        method: 'POST',
        body: JSON.stringify({ outcome }),
      })
      resource.reload()
      notifyDemoMoneyChanged()
      if (p.status === 'SUCCESS') {
        if (p.purpose === 'TRANSFER') {
          router.push(`/customer/transfers/acquisitions/${p.acquisitionId}`)
        } else if (p.purpose === 'GROUP_CONTRIBUTION') {
          router.push(`/customer/groups/${p.bookingId}`)
        } else setConfirmation({ payment: p, deadline: Date.now() + 15000 })
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể thực hiện thanh toán mô phỏng.')
    } finally {
      setBusy(false)
    }
  }
  if (resource.loading && !resource.data)
    return (
      <p role="status" className="p-8">
        Đang tải giao dịch…
      </p>
    )
  if (resource.error || !resource.data)
    return (
      <p role="alert" className="p-8 text-red-700">
        {resource.error}
      </p>
    )
  const p = resource.data
  const resultPath = bookingResultPath(
    p,
    bookingResource.data?.booking,
    user,
    owned.data?.map((f) => f.id),
  )
  if (p.provider === 'CASH')
    return (
      <div className="relative isolate min-h-[85vh] py-8 sm:py-12">
        <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
          <div className="absolute -left-32 top-0 size-[420px] rounded-full bg-emerald-500/10 blur-3xl" />
        </div>
        <div className="mx-auto max-w-xl px-4 sm:px-6">
          <CashPaymentResult id={p.id} resultHref={resultPath} />
        </div>
      </div>
    )
  if (['SEPAY', 'PAYOS'].includes(p.provider))
    return (
      <div className="relative isolate min-h-[85vh] py-8 sm:py-12">
        <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
          <div className="absolute -left-32 top-0 size-[420px] rounded-full bg-emerald-500/10 blur-3xl" />
          <div className="absolute -right-24 bottom-1/4 size-[380px] rounded-full bg-amber-400/10 blur-3xl" />
        </div>
        <div className="mx-auto max-w-xl space-y-5 px-4 sm:px-6">
          <div className="space-y-1">
            <div className="flex items-center gap-2 text-xs font-semibold text-emerald-700">
              <span className="text-slate-400">Chọn sân</span>
              <span className="text-slate-300">→</span>
              <span className="text-slate-400">Tóm tắt</span>
              <span className="text-slate-300">→</span>
              <span className="text-emerald-700">Thanh toán</span>
            </div>
            <h1 className="text-2xl sm:text-3xl font-black tracking-tight text-slate-950">
              Thanh toán lượt đặt
            </h1>
          </div>
          <BankPaymentInstructions payment={p} resultHref={resultPath} />
          {bookingResource.data && (
            <RefundPolicyNotice />
          )}
          {acquisitionResource.data?.financeTerms && (
            <TransferFinanceNotice terms={acquisitionResource.data.financeTerms} />
          )}
          {resultPath && (
            <div className="text-center pt-1">
              <Link
                href={resultPath}
                className="text-xs font-semibold text-slate-500 hover:text-emerald-700 underline"
              >
                Quay lại lượt đặt
              </Link>
            </div>
          )}
        </div>
      </div>
    )
  return (
    <div className="mx-auto max-w-xl px-5 py-10">
      <p className="text-sm font-bold text-amber-700">
        {runtime.data?.mode === 'REAL' ? 'GIAO DỊCH MÔ PHỎNG CŨ' : 'MÔI TRƯỜNG THANH TOÁN MÔ PHỎNG'}
      </p>
      <h1 className="mt-4 text-3xl font-black">Thanh toán lượt đặt</h1>
      <p className="mt-3 text-sm text-slate-500">
        Không phát sinh giao dịch tiền thật. Kết quả được máy chủ ghi nhận.
      </p>
      <section className="my-6 space-y-4 rounded-2xl border p-6">
        <p className="text-sm text-slate-500">
          Mã giao dịch <span className="break-all">{p.providerReference}</span>
        </p>
        <p className="text-3xl font-bold text-emerald-700">{money(p.amount, p.currency)}</p>
        <p className="text-sm">Trạng thái: {displayLabel(p.status)}</p>
        {p.status === 'PENDING' && <Countdown expiresAt={p.expiresAt} />}
      </section>
      {bookingResource.data && (
        <RefundPolicyNotice />
      )}
      {acquisitionResource.data?.financeTerms && (
        <TransferFinanceNotice terms={acquisitionResource.data.financeTerms} />
      )}
      {p.status === 'SUCCESS' && p.purpose !== 'TRANSFER' && <PaymentSynchronization id={id} />}
      {error && (
        <p role="alert" className="mb-4 rounded-lg bg-red-50 p-4 text-sm text-red-700">
          {error}
        </p>
      )}
      {waiting && (
        <p role="status" className="mb-4 text-emerald-700">
          Đã nhận thanh toán. Đang xác nhận lượt đặt…
        </p>
      )}
      {p.status === 'PENDING' && runtime.data?.mode === 'REAL' && (
        <p role="status" className="my-4 rounded-xl bg-amber-50 p-4 text-sm text-amber-900">
          Đây là giao dịch mô phỏng cũ, không thể chuyển thành thanh toán tiền thật. Lượt đặt mới sử
          dụng QR SePay của cơ sở.
        </p>
      )}
      {p.status === 'PENDING' && runtime.data?.mode !== 'REAL' && (
        <div className="grid gap-3">
          <Button
            className="h-12 bg-emerald-600 text-white"
            onClick={() => complete('SUCCESS')}
            disabled={busy || waiting}
          >
            {busy ? 'Đang xử lý…' : 'Thanh toán mô phỏng'}
          </Button>
          <Button variant="outline" onClick={() => complete('FAILED')} disabled={busy || waiting}>
            Giả lập thanh toán thất bại
          </Button>
        </div>
      )}
      {bookingResource.error && (
        <p role="alert" className="my-4 text-red-700">
          {bookingResource.error}
          <button className="ml-3 underline" onClick={bookingResource.reload}>
            Tải lại lượt đặt
          </button>
        </p>
      )}
      {p.status === 'SUCCESS' && !waiting && resultPath && (
        <Link
          href={resultPath}
          className="block rounded-full bg-emerald-600 p-3 text-center text-sm text-white"
        >
          Xem kết quả đặt sân
        </Link>
      )}
      {p.status === 'FAILED' && resultPath && (
        <Link className="text-emerald-700 underline" href={resultPath}>
          Xem giao dịch và thử lại
        </Link>
      )}
    </div>
  )
}
