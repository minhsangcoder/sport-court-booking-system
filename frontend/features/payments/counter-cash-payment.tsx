'use client'
import Link from 'next/link'
import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { usePolling } from '@/lib/use-polling'
import { money } from '@/features/schedule/types'
import type { Payment } from '@/features/booking/types'
import { PaymentSynchronization } from '@/features/booking/payment-synchronization'

function CheckCircleIcon() {
  return (
    <svg className="size-6 text-emerald-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
      <polyline points="22 4 12 14.01 9 11.01" />
    </svg>
  )
}

function ArrowRightIcon() {
  return (
    <svg className="size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="5" y1="12" x2="19" y2="12" />
      <polyline points="12 5 19 12 12 19" />
    </svg>
  )
}

export function CounterCashPayment({
  payment,
  onReceived,
}: {
  payment: Payment
  onReceived: (id: string) => void
}) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const pending = useRef(false)
  const [error, setError] = useState('')
  async function collect() {
    if (pending.current) return
    pending.current = true
    setBusy(true)
    setError('')
    try {
      const cash = await api<Payment>(`/payments/${payment.id}/counter-cash`, { method: 'POST' })
      onReceived(cash.id)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Chưa ghi nhận được tiền mặt.')
    } finally {
      pending.current = false
      setBusy(false)
    }
  }
  return (
    <div className="space-y-3 rounded-xl border border-emerald-200 p-4">
      {!confirming ? (
        <Button onClick={() => setConfirming(true)}>Thanh toán bằng tiền mặt</Button>
      ) : (
        <>
          <p>
            Chỉ xác nhận sau khi đã nhận đủ{' '}
            <strong>{money(payment.amount, payment.currency)}</strong> tiền mặt từ khách. Nhắc khách
            ngừng chuyển khoản theo QR cũ.
          </p>
          <div className="flex flex-wrap gap-3">
            <Button disabled={busy} onClick={collect}>
              {busy ? 'Đang ghi nhận…' : 'Xác nhận đã nhận đủ tiền mặt'}
            </Button>
            <Button variant="outline" disabled={busy} onClick={() => setConfirming(false)}>
              Quay lại
            </Button>
          </div>
        </>
      )}
      {error && (
        <p role="alert" className="text-red-700">
          {error}
        </p>
      )}
    </div>
  )
}

export function CashPaymentResult({
  id,
  onApplied,
  resultHref,
}: {
  id: string
  onApplied?: () => void
  resultHref?: string | null
}) {
  const state = useApi<{ state: string }>(`/payments/${id}/synchronization`)
  const notified = useRef(false)
  useEffect(() => {
    if (state.data?.state === 'ACKNOWLEDGED' && !notified.current) {
      notified.current = true
      onApplied?.()
    }
  }, [state.data?.state, onApplied])
  usePolling(
    async (signal) => {
      const next = await api<{ state: string }>(`/payments/${id}/synchronization`, { signal })
      if (next.state === 'ACKNOWLEDGED' && !signal.aborted) {
        if (!notified.current) {
          notified.current = true
          onApplied?.()
          state.reload()
        }
        return false
      }
      if (next.state === 'REQUIRES_REVIEW') {
        state.reload()
        return false
      }
    },
    {
      scope: id,
      interval: 2000,
      maxAttempts: 15,
      enabled: state.data?.state === 'AWAITING_BOOKING',
    },
  )
  return (
    <section className="relative isolate overflow-hidden rounded-3xl border border-emerald-200/90 bg-white p-6 sm:p-7 shadow-xl shadow-slate-900/5 backdrop-blur-xs space-y-5 text-slate-900 home-rise">
      <div className="flex items-center gap-3.5">
        <div className="flex size-11 shrink-0 items-center justify-center rounded-2xl bg-emerald-100 text-emerald-700 shadow-2xs">
          <CheckCircleIcon />
        </div>
        <div>
          <h2 className="text-lg font-bold text-slate-900">Đã ghi nhận thanh toán bằng tiền mặt</h2>
          <p className="text-xs text-slate-500">Giao dịch thu tiền mặt tại quầy đã được tạo thành công</p>
        </div>
      </div>
      <div className="rounded-2xl bg-slate-50/80 p-4 border border-slate-100">
        <PaymentSynchronization key={state.data?.state} id={id} />
      </div>
      {!onApplied && resultHref && (
        <Link
          className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-sm shadow-xs hover:shadow-md transition-all active:scale-[0.99]"
          href={resultHref}
        >
          <span>Xem trạng thái lượt đặt</span>
          <ArrowRightIcon />
        </Link>
      )}
    </section>
  )
}
