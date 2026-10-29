'use client'
import Image from 'next/image'
import { CounterCashPayment, CashPaymentResult } from './counter-cash-payment'
import Link from 'next/link'
import { useState } from 'react'
import { api, ApiError } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useAuth } from '@/lib/auth-context'
import { usePolling } from '@/lib/use-polling'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import type { Payment } from '@/features/booking/types'
import type { Acquisition } from '@/features/transfers/types'
import { Countdown, useRemaining } from '@/features/booking/countdown'
import type { BankQrSession } from './types'

const states: Record<string, string> = {
  INITIALIZING: 'Đang tạo QR thanh toán',
  CREATE_UNKNOWN: 'Đang kiểm tra kết quả tạo QR',
  READY: 'Chờ chuyển khoản',
  PARTIAL: 'Đã nhận một phần, chưa đủ tiền',
  VERIFIED: 'Đã xác minh đủ tiền vào cơ sở',
  EXPIRED: 'Đã hết thời hạn thanh toán',
  REVIEW: 'Cần cơ sở đối soát giao dịch',
}
type InstructionProps = {
  payment: Payment
  onApplied?: () => void
  resultHref?: string | null
}
export function BankPaymentInstructions(props: InstructionProps) {
  const { user } = useAuth()
  return <Instructions key={`${user?.id}:${props.payment.id}`} {...props} />
}
function Instructions({ payment, onApplied, resultHref }: InstructionProps) {
  const resource = useApi<BankQrSession>(`/payments/${payment.id}/qr`)
  const [cashId, setCashId] = useState<string | null>(null)
  const [latest, setLatest] = useState<BankQrSession | null>(null)
  const [applied, setApplied] = useState(false)
  const [stopped, setStopped] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [timedOut, setTimedOut] = useState(false)
  const [applicationNotice, setApplicationNotice] = useState('')
  const session = latest ?? resource.data
  const remaining = useRemaining(session?.expiresAt)
  const image = useApi<string>(session?.qrCode ? `/payments/${payment.id}/qr-image` : null)
  usePolling(
    async (signal) => {
      try {
        const next = await api<BankQrSession>(`/payments/${payment.id}/qr`, { signal })
        if (signal.aborted) return
        setLatest(next)
        if (next.state === 'VERIFIED') {
          let ready = false
          if (payment.purpose === 'TRANSFER') {
            const acquisition = await api<Acquisition>(
              `/transfers/acquisitions/${payment.acquisitionId}`,
              { signal },
            )
            ready = acquisition.state === 'SUCCESS'
            if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(acquisition.state)) {
              setApplicationNotice(
                'Chuyển nhượng không hoàn tất. Tiền đã nhận được ghi nhận để cơ sở hoàn lại; bạn chưa có quyền sử dụng.',
              )
              return false
            }
          } else if (payment.purpose === 'GROUP_CONTRIBUTION') {
            const group = await api<{ state: string }>(`/groups/${payment.bookingId}`, { signal })
            ready = ['CONFIRMED', 'CHECKED_IN', 'COMPLETED'].includes(group.state)
            if (['CANCELLED', 'EXPIRED'].includes(group.state)) {
              setApplicationNotice(
                'Nhóm đã hủy hoặc hết hạn. Tiền không áp dụng được sẽ được cơ sở đối soát và hoàn lại.',
              )
              return false
            }
            if (!ready && next.applicationState === 'ACKNOWLEDGED') {
              setApplicationNotice(
                'Phần góp đã được ghi nhận; nhóm cần thanh toán đủ các phần còn lại trước khi dùng sân.',
              )
              return false
            }
          } else {
            ready = next.applicationState === 'ACKNOWLEDGED'
          }
          if (ready && !signal.aborted) {
            setApplied(true)
            onApplied?.()
            return false
          }
          if (next.applicationState === 'REQUIRES_REVIEW') {
            setApplicationNotice(
              'Tiền đã được ngân hàng ghi nhận, đang chờ cơ sở đối soát việc áp dụng. Chưa cấp quyền sử dụng khi kết quả chưa rõ.',
            )
            return false
          }
        }
        if (['EXPIRED', 'REVIEW'].includes(next.state)) return false
      } catch (failure) {
        if (signal.aborted) return
        if (failure instanceof ApiError && [401, 403].includes(failure.status)) {
          setStopped(true)
          setLatest(null)
        }
        setError(
          failure instanceof Error ? failure.message : 'Chưa tải được trạng thái thanh toán.',
        )
        if (failure instanceof ApiError && [401, 403].includes(failure.status)) return false
      }
    },
    {
      enabled:
        !applied &&
        !stopped &&
        !timedOut &&
        !applicationNotice &&
        Boolean(session) &&
        !['EXPIRED', 'REVIEW'].includes(session?.state ?? ''),
      scope: payment.id,
      interval: 4000,
      immediate: true,
      deadline: session ? Date.parse(session.expiresAt) + 10 * 60_000 : undefined,
      onTimeout: () => setTimedOut(true),
    },
  )
  async function refresh() {
    if (busy || stopped) return
    setBusy(true)
    setError('')
    try {
      setLatest(await api<BankQrSession>(`/payments/${payment.id}/refresh`, { method: 'POST' }))
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Chưa yêu cầu được kiểm tra giao dịch.')
    } finally {
      setBusy(false)
    }
  }
  const effectiveCashId = cashId || session?.cashPaymentId
  const showQr =
    !stopped && session?.state === 'READY' && session.qrCode && remaining !== null && remaining > 0
  const result =
    resultHref !== undefined
      ? resultHref
      : payment.purpose === 'TRANSFER'
        ? `/customer/transfers/acquisitions/${payment.acquisitionId}`
        : payment.purpose === 'GROUP_CONTRIBUTION'
          ? `/customer/groups/${payment.bookingId}`
          : `/customer/bookings/${payment.bookingId}`
  return effectiveCashId ? (
    <CashPaymentResult id={effectiveCashId} onApplied={onApplied} resultHref={result} />
  ) : (
    <section className="space-y-4 rounded-2xl border bg-white p-5 text-slate-900">
      {session?.counterCashAllowed && (
        <CounterCashPayment payment={payment} onReceived={setCashId} />
      )}
      <h2 className="text-lg font-bold">Chuyển khoản cho cơ sở</h2>
      <p className="text-sm">
        Quyền sử dụng sân được cấp sau khi máy chủ xác minh đủ tiền và hoàn tất áp dụng hoặc bàn
        giao.
      </p>
      {resource.loading && !session && <p role="status">Đang tải hướng dẫn chuyển khoản…</p>}
      {session && !stopped && (
        <>
          <p role="status" className="font-semibold text-emerald-800">
            {states[session.state] ?? 'Đang kiểm tra giao dịch'}
          </p>
          <dl className="grid grid-cols-2 gap-3 text-sm">
            <dt>Cần thanh toán</dt>
            <dd className="text-right font-bold">{money(Number(session.amount))}</dd>
            <dt>Đã nhận</dt>
            <dd className="text-right">{money(Number(session.amountReceived))}</dd>
            <dt>Còn thiếu</dt>
            <dd className="text-right font-bold">
              {money(Math.max(0, Number(session.amount) - Number(session.amountReceived)))}
            </dd>
            {session.accountNumber && (
              <>
                <dt>Ngân hàng (BIN)</dt>
                <dd className="text-right">{session.bankBin}</dd>
                <dt>Số tài khoản cơ sở</dt>
                <dd className="break-all text-right font-semibold">{session.accountNumber}</dd>
                <dt>Người nhận</dt>
                <dd className="text-right">{session.accountName}</dd>
              </>
            )}
            <dt>Nội dung chuyển khoản</dt>
            <dd className="text-right font-mono font-bold">{session.description}</dd>
          </dl>
          {showQr && (
            <>
              {image.data && (
                <Image
                  unoptimized
                  src={image.data}
                  width={320}
                  height={320}
                  alt="QR chuyển khoản thanh toán cho cơ sở"
                  className="mx-auto h-auto max-w-full"
                />
              )}
              {(image.error || image.loading) && (
                <p role="status" className="text-sm">
                  {image.error || 'Đang dựng ảnh QR…'}{' '}
                  {image.error && (
                    <button className="underline" onClick={image.reload}>
                      Tải lại ảnh QR
                    </button>
                  )}
                </p>
              )}
              <Countdown expiresAt={session.expiresAt} />
              <p className="text-xs text-slate-600">
                QR này dùng thanh toán. QR vào sân chỉ xuất hiện trong lượt đặt sau khi đã xác minh
                đủ tiền. Giữ nguyên nội dung và số tiền khi chuyển.
              </p>
            </>
          )}
          {session.state === 'READY' && remaining !== null && remaining > 0 && (
            <>
              {!session.qrCode && (
                <p role="status" className="text-sm">
                  QR đang được khôi phục. Bạn có thể mở đơn đã tạo trên payOS để xem QR và thanh
                  toán.
                </p>
              )}
              {session.checkoutUrl?.startsWith('https://pay.payos.vn/') && (
                <a
                  href={session.checkoutUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="block text-emerald-700 underline"
                >
                  Mở trang thanh toán payOS
                </a>
              )}
            </>
          )}
          {session.state === 'PARTIAL' && (
            <p className="text-sm text-amber-800">
              Chưa đủ tiền để sử dụng sân. Kiểm tra giao dịch trước khi chuyển phần còn thiếu; không
              chuyển lại toàn bộ.
            </p>
          )}
          {session.state === 'VERIFIED' && !applied && !applicationNotice && (
            <p className="text-sm">
              Đang áp dụng thanh toán vào lượt đặt. Quyền sử dụng được cấp khi hệ thống xác nhận đủ
              tiền và ghi nhận lượt đặt thành công.
            </p>
          )}
          {applicationNotice && (
            <p role="status" className="text-sm text-amber-800">
              {applicationNotice}
            </p>
          )}
          {['EXPIRED', 'REVIEW'].includes(session.state) && (
            <p className="text-sm text-amber-800">
              Không chuyển thêm theo QR cũ. Nếu đã chuyển, liên hệ cơ sở đối soát; tiền không áp
              dụng sẽ được ghi khoản cần hoàn.
            </p>
          )}
        </>
      )}
      {(error || resource.error) && (
        <p role="alert" className="text-sm text-red-700">
          {error || resource.error}
        </p>
      )}
      {timedOut && (
        <p role="status" className="text-sm text-amber-800">
          Đã dừng theo dõi tại màn hình này. Kết quả vẫn được máy chủ đối soát; tải lại giao dịch để
          kiểm tra.
        </p>
      )}
      <div className="flex flex-wrap gap-3">
        <Button variant="outline" onClick={refresh} disabled={busy || stopped}>
          {busy ? 'Đang yêu cầu kiểm tra…' : 'Kiểm tra giao dịch'}
        </Button>
        {!onApplied && result && (
          <Link href={result} className="rounded-lg border px-4 py-2 text-sm text-emerald-700">
            {applied ? 'Xem lượt đặt đã xác nhận' : 'Xem trạng thái lượt đặt'}
          </Link>
        )}
      </div>
    </section>
  )
}
