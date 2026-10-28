'use client'
import Link from 'next/link'
import dynamic from 'next/dynamic'
import { useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { api, ApiError } from '@/lib/api'
import { usePolling } from '@/lib/use-polling'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import { Countdown, useRemaining } from '@/features/booking/countdown'
import type { Payment } from '@/features/booking/types'
import { TransferSynchronization } from './transfer-synchronization'
import { type Acquisition, transferStates } from './types'
import { TransferFinanceNotice } from './finance-notice'
import { useAuth } from '@/lib/auth-context'
const PeerAcquisitionPage = dynamic(() => import('./peer-acquisition'), {
  loading: () => (
    <p role="status" className="p-8">
      Đang mở giao dịch trực tiếp…
    </p>
  ),
})
export function AcquisitionPage({ id }: { id: string }) {
  const { user } = useAuth()
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded key={`${user?.id}:${id}`} id={id} />
    </RequireAuth>
  )
}
function Loaded({ id }: { id: string }) {
  const router = useRouter()
  const r = useApi<Acquisition>(`/transfers/acquisitions/${id}`)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [observed, setObserved] = useState<{ id: string; value: Acquisition } | null>(null)
  const [pollingStopped, setPollingStopped] = useState(false)
  const current = observed?.id === id ? observed.value : r.data
  const remaining = useRemaining(current?.expiresAt)
  const payKey = useRef<string | null>(null)
  usePolling(
    async (signal) => {
      try {
        const value = await api<Acquisition>(`/transfers/acquisitions/${id}`, { signal })
        if (signal.aborted) return
        setObserved({ id, value })
        if (!['PENDING', 'PENDING_HANDOFF', 'PENDING_AUDIT'].includes(value.state)) return false
      } catch (failure) {
        if (signal.aborted) return
        if (failure instanceof ApiError && [401, 403].includes(failure.status)) {
          setPollingStopped(true)
          return false
        }
        setError(failure instanceof Error ? failure.message : 'Chưa tải được tiến độ bàn giao.')
      }
    },
    {
      enabled:
        !pollingStopped &&
        current?.protocol !== 'PEER_RESALE_V1' &&
        Boolean(current && ['PENDING', 'PENDING_HANDOFF', 'PENDING_AUDIT'].includes(current.state)),
      scope: id,
      interval: 4000,
      deadline: current ? Date.parse(current.expiresAt) + 10 * 60_000 : undefined,
      onTimeout: () => {
        setPollingStopped(true)
        setError(
          'Đã dừng theo dõi tại màn hình. Tải lại giao dịch để xem kết quả đối soát; không thanh toán lại khi chưa rõ kết quả.',
        )
      },
    },
  )
  async function pay() {
    if (!r.data || busy) return
    setBusy(true)
    setError('')
    try {
      payKey.current ??= crypto.randomUUID()
      const p = await api<Payment>('/payments', {
        method: 'POST',
        headers: { 'Idempotency-Key': payKey.current },
        body: JSON.stringify({ bookingId: r.data.bookingId, acquisitionId: id }),
      })
      if (p.status === 'FAILED') {
        payKey.current = null
        throw new Error('Lần thanh toán trước thất bại. Bấm thanh toán để thử lại.')
      }
      router.push(`/customer/payments/${p.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể tạo thanh toán.')
      r.reload()
    } finally {
      setBusy(false)
    }
  }
  async function cancel() {
    if (!window.confirm('Hủy giao dịch chưa hoàn tất và mở lại tin cho người khác?')) return
    setBusy(true)
    setError('')
    try {
      await api(`/transfers/acquisitions/${id}/cancel`, { method: 'POST' })
      r.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể hủy giao dịch.')
    } finally {
      setBusy(false)
    }
  }
  if (r.loading && !r.data)
    return (
      <p role="status" className="p-8">
        Đang tải giao dịch…
      </p>
    )
  if (!r.data)
    return (
      <p role="alert" className="p-8 text-red-700">
        {r.error}
        <button className="ml-4 underline" onClick={r.reload}>
          Thử lại
        </button>
      </p>
    )
  const a = current ?? r.data
  if (a.protocol === 'PEER_RESALE_V1') return <PeerAcquisitionPage id={id} />
  return (
    <div className="mx-auto max-w-xl px-5 py-10">
      <Link href="/customer/transfers" className="text-sm text-slate-500">
        Chuyển nhượng / Giao dịch
      </Link>
      <h1 className="mt-6 text-3xl font-black">{transferStates[a.state]}</h1>
      <section className="mt-6 space-y-4 rounded-2xl border p-6">
        <p className="text-3xl font-bold text-emerald-700">{money(a.amount, a.currency)}</p>
        <p className="break-all text-xs text-slate-500">Mã giao dịch {id}</p>
        <Link
          className="block text-sm text-emerald-700 underline"
          href={`/transfers/${a.listingId}`}
        >
          Xem sân và khung giờ
        </Link>
        {a.state === 'PENDING' && <Countdown expiresAt={a.expiresAt} />}
      </section>
      {a.financeTerms && <TransferFinanceNotice terms={a.financeTerms} />}
      {(error || r.error) && (
        <p role="alert" className="mt-5 text-red-700">
          {error || r.error}
        </p>
      )}
      {a.state === 'PENDING' && (
        <div className="mt-6 grid gap-3">
          <Button
            className="h-12 bg-emerald-600 text-white"
            onClick={pay}
            disabled={busy || remaining === 0}
          >
            Thanh toán để nhận lượt đặt
          </Button>
          <Button variant="outline" onClick={cancel} disabled={busy}>
            Hủy giao dịch
          </Button>
        </div>
      )}
      {a.state === 'PENDING_HANDOFF' && (
        <p role="status" className="mt-6 rounded-xl bg-emerald-50 p-5 text-emerald-800">
          Đã xác nhận thanh toán. Đang bàn giao quyền sử dụng và mã xác nhận vào sân; không chờ cơ
          sở trả người bán…
        </p>
      )}
      {a.state === 'SUCCESS' && (
        <Link
          href={`/customer/bookings/${a.bookingId}`}
          className="mt-6 block rounded-full bg-emerald-600 p-3 text-center text-white"
        >
          Mở lượt đặt và mã xác nhận vào sân của bạn
        </Link>
      )}
      {['PENDING_HANDOFF', 'PENDING_AUDIT', 'SUCCESS'].includes(a.state) && (
        <TransferSynchronization id={a.listingId} />
      )}
      {a.state === 'PENDING_AUDIT' && (
        <p className="mt-6 rounded-xl bg-amber-50 p-5 text-sm text-amber-900">
          Giao dịch đang đối soát. Quyền sử dụng chỉ được cấp sau khi bàn giao thành công.
        </p>
      )}
    </div>
  )
}
