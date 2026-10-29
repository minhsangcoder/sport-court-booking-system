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
    <section className="space-y-4 rounded-xl border p-4">
      <h2 className="font-bold">Đã ghi nhận thanh toán bằng tiền mặt</h2>
      <PaymentSynchronization key={state.data?.state} id={id} />
      {!onApplied && resultHref && (
        <Link className="text-emerald-700 underline" href={resultHref}>
          Xem trạng thái lượt đặt
        </Link>
      )}
    </section>
  )
}
