'use client'
import Link from 'next/link'
import { useEffect, useRef, useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Button } from '@/components/ui/button'
import { demoVnd } from '@/features/facility/demo-report'

type Settlement = {
  acquisitionId: string
  bookingId: string
  sellerId: string
  buyerId: string
  amount: string
  feeBps: number
  feeAmount: string
  sellerNetAmount: string
  payoutState: string
  paidAt?: string | null
}
type Refund = {
  id: string
  bookingId: string
  baseAmount: string
  rateBps: number
  targetAmount: string
  yourRefundAmount?: string
  recipientKind?: string
  recipientLabel?: string
  reason: string
  state: string
  refundedAt?: string | null
}
type Finance = {
  mode: 'DEMO'
  canPaySeller?: boolean
  canRefund?: boolean
  settlements: Settlement[]
  refunds: Refund[]
}
const payoutLabels: Record<string, string> = {
  UNPAID: 'Chờ cơ sở trả mô phỏng',
  FAILED: 'Trả mô phỏng thất bại — vẫn còn nghĩa vụ',
  PAID: 'Đã trả mô phỏng',
  NOT_REQUIRED: 'Không có khoản phải trả',
}
const refundLabels: Record<string, string> = {
  PENDING: 'Chờ cơ sở hoàn mô phỏng',
  FAILED: 'Hoàn mô phỏng thất bại — đang chờ thử lại',
  REFUNDED: 'Đã hoàn mô phỏng',
  NO_REFUND: 'Không phát sinh tiền hoàn',
}
export function notifyDemoMoneyChanged() {
  window.dispatchEvent(new Event('sporthub:demo-money-changed'))
  try {
    localStorage.setItem('sporthub:demo-money-changed', `${Date.now()}:${crypto.randomUUID()}`)
  } catch {
    /* A blocked browser store does not affect the committed server operation. */
  }
}
const notify = notifyDemoMoneyChanged

export function FacilityDemoMoneyPanel({
  facilityId,
  canReadBooking = true,
}: {
  facilityId: string
  canReadBooking?: boolean
}) {
  const resource = useApi<Finance>(`/bookings/demo-finance/facility/${facilityId}`)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const intents = useRef<Record<string, { outcome: string; key: string }>>({})
  useEffect(() => {
    window.addEventListener('sporthub:demo-money-changed', resource.reload)
    return () => window.removeEventListener('sporthub:demo-money-changed', resource.reload)
  }, [resource.reload])
  async function execute(
    kind: 'settlements' | 'refunds',
    id: string,
    outcome: 'SUCCESS' | 'FAILED',
  ) {
    if (busy) return
    const target = `${kind}:${id}`
    if (intents.current[target]?.outcome !== outcome)
      intents.current[target] = { outcome, key: crypto.randomUUID() }
    setBusy(target)
    setError('')
    try {
      await api(
        `/bookings/demo-finance/${kind}/${id}/${kind === 'settlements' ? 'pay-seller' : 'complete'}`,
        {
          method: 'POST',
          headers: { 'Idempotency-Key': intents.current[target].key },
          body: JSON.stringify({ outcome }),
        },
      )
      notify()
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : 'Chưa ghi nhận được kết quả mô phỏng. Hãy tải lại hoặc thử cùng thao tác.',
      )
    } finally {
      setBusy('')
    }
  }
  const valid =
    resource.data &&
    Array.isArray(resource.data.settlements) &&
    Array.isArray(resource.data.refunds)
  const data = !resource.loading && valid ? resource.data : null
  return (
    <section className="mt-6 space-y-4 rounded-2xl border bg-white p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-bold">Trả người chuyển nhượng &amp; hoàn tiền mô phỏng</h2>
        <Button
          variant="outline"
          onClick={resource.reload}
          disabled={Boolean(busy) || resource.loading}
        >
          Tải lại nghĩa vụ
        </Button>
      </div>
      <p className="text-sm text-amber-800">
        Thao tác giả lập được lưu trong hệ thống, không chuyển tiền thật. B đã nhận quyền không phải
        chờ cơ sở trả người bán.
      </p>
      {resource.loading && (
        <p role="status" className="text-sm text-slate-500">
          Đang tải khoản phải thực hiện…
        </p>
      )}
      {(error || resource.error) && (
        <p role="alert" className="text-sm text-red-700">
          {error || resource.error}
        </p>
      )}
      {!resource.loading && resource.data && !valid && (
        <p role="alert" className="text-sm text-red-700">
          Chưa tải được dữ liệu nghĩa vụ hợp lệ. Hãy tải lại.
        </p>
      )}
      {data?.canPaySeller && (
        <div className="space-y-3">
          <h3 className="font-semibold">Khoản trả người chuyển nhượng</h3>
          {data.settlements.map((item) => (
            <article key={item.acquisitionId} className="space-y-3 rounded-xl border p-4">
              <p className="font-medium">Cần trả người bán: {demoVnd(item.sellerNetAmount)}</p>
              <p className="text-sm text-slate-600">
                Người mua trả {demoVnd(item.amount)} · phí cơ sở{' '}
                {(item.feeBps / 100).toLocaleString('vi-VN')}%: {demoVnd(item.feeAmount)}
              </p>
              <p className="text-sm">
                {payoutLabels[item.payoutState] || 'Đang đồng bộ trạng thái'}
              </p>
              {canReadBooking && (
                <Link
                  href={`/customer/bookings/${item.bookingId}`}
                  className="block text-sm text-emerald-700 underline"
                >
                  Xem lượt đặt liên quan
                </Link>
              )}
              {['UNPAID', 'FAILED'].includes(item.payoutState) && (
                <div className="flex flex-wrap gap-3">
                  <Button
                    onClick={() => execute('settlements', item.acquisitionId, 'SUCCESS')}
                    disabled={Boolean(busy)}
                    className="bg-emerald-600 text-white"
                  >
                    Trả tiền mô phỏng cho A
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => execute('settlements', item.acquisitionId, 'FAILED')}
                    disabled={Boolean(busy)}
                  >
                    Giả lập trả A thất bại
                  </Button>
                </div>
              )}
            </article>
          ))}
          {data.settlements.length === 0 && (
            <p className="text-sm text-slate-500">Chưa có khoản trả người chuyển nhượng.</p>
          )}
        </div>
      )}
      {data?.canRefund && (
        <div className="space-y-3">
          <h3 className="font-semibold">Yêu cầu hoàn khách</h3>
          {data.refunds.map((item) => (
            <article key={item.id} className="space-y-3 rounded-xl border p-4">
              <p className="font-medium">Khoản hoàn: {demoVnd(item.targetAmount)}</p>
              {item.recipientKind === 'GUEST' && (
                <p className="text-sm">
                  Hoàn cho khách vãng lai: {item.recipientLabel}. Nhân viên tạo lượt đặt không phải
                  người nhận.
                </p>
              )}
              <p className="text-sm text-slate-600">
                {item.reason === 'TECHNICAL_TRANSFER_FAILURE'
                  ? 'Hoàn đủ 100% do bàn giao đã xác nhận thất bại'
                  : `Theo chính sách ${(item.rateBps / 100).toLocaleString('vi-VN')}% trên ${demoVnd(item.baseAmount)}`}
              </p>
              <p className="text-sm">{refundLabels[item.state] || 'Đang đồng bộ trạng thái'}</p>
              {canReadBooking && (
                <Link
                  href={`/customer/bookings/${item.bookingId}`}
                  className="block text-sm text-emerald-700 underline"
                >
                  Xem lượt đặt liên quan
                </Link>
              )}
              {['PENDING', 'FAILED'].includes(item.state) && (
                <div className="flex flex-wrap gap-3">
                  <Button
                    onClick={() => execute('refunds', item.id, 'SUCCESS')}
                    disabled={Boolean(busy)}
                    className="bg-emerald-600 text-white"
                  >
                    Hoàn tiền mô phỏng
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => execute('refunds', item.id, 'FAILED')}
                    disabled={Boolean(busy)}
                  >
                    Giả lập hoàn thất bại
                  </Button>
                </div>
              )}
            </article>
          ))}
          {data.refunds.length === 0 && (
            <p className="text-sm text-slate-500">Chưa có yêu cầu hoàn khách.</p>
          )}
        </div>
      )}
    </section>
  )
}
