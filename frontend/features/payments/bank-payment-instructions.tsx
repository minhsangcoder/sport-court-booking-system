'use client'
import Image from 'next/image'
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
import type { BankCatalog, BankQrSession } from './types'
import { CashPaymentResult } from './counter-cash-payment'
import { CourtLines } from '@/features/home/home-art'
import {
  AlertCircle,
  ArrowRight,
  Building2,
  Check,
  CheckCircle2,
  Copy,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react'

const states: Record<string, string> = {
  INITIALIZING: 'Đang tạo QR thanh toán',
  CREATE_UNKNOWN: 'Đang kiểm tra kết quả tạo QR',
  READY: 'Chờ chuyển khoản',
  PARTIAL: 'Đã nhận một phần, chưa đủ tiền',
  VERIFIED: 'Đã xác minh đủ tiền vào cơ sở',
  EXPIRED: 'QR không còn hiệu lực',
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
  const [latest, setLatest] = useState<BankQrSession | null>(null)
  const [applied, setApplied] = useState(false)
  const [stopped, setStopped] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [timedOut, setTimedOut] = useState(false)
  const [applicationNotice, setApplicationNotice] = useState('')
  const [copiedKey, setCopiedKey] = useState<string | null>(null)

  function copy(text: string | null | undefined, key: string) {
    if (!text) return
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(text)
      setCopiedKey(key)
      setTimeout(() => setCopiedKey(null), 2000)
    }
  }

  const session = latest ?? resource.data
  const catalog = useApi<BankCatalog>(session?.bankBin ? '/payments/banks' : null)
  const bank = catalog.data?.banks?.find((item) => item.bin === session?.bankBin)
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
                'Nhóm đã hủy hoặc hết hạn nên không được dùng sân. Khoản góp đã ghi nhận không được hoàn lại.',
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

  const effectiveCashId = session?.cashPaymentId
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

  if (effectiveCashId) {
    return <CashPaymentResult id={effectiveCashId} onApplied={onApplied} resultHref={result} />
  }

  const amountNumber = Number(session?.amount ?? 0)
  const receivedNumber = Number(session?.amountReceived ?? 0)
  const remainingNumber = Math.max(0, amountNumber - receivedNumber)

  return (
    <section className="relative isolate overflow-hidden rounded-3xl border border-slate-200/90 bg-white p-5 sm:p-7 shadow-xl shadow-slate-900/5 backdrop-blur-xs space-y-6 text-slate-900 home-rise">
      <CourtLines className="pointer-events-none absolute -right-20 -top-12 w-80 rotate-[-6deg] text-emerald-600/[0.04]" />

      {/* Header and status */}
      <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="text-xl font-extrabold tracking-tight text-slate-950">Chuyển khoản cho cơ sở</h2>
        {session && !stopped && (
          <div className="inline-flex shrink-0 items-center gap-2 rounded-full border border-emerald-500/25 bg-emerald-50/80 px-3 py-1 text-xs font-semibold text-emerald-800 shadow-2xs">
            <span className="relative flex size-2">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-500 opacity-75" />
              <span className="relative inline-flex size-2 rounded-full bg-emerald-500" />
            </span>
            {states[session.state] ?? 'Chờ chuyển khoản'}
          </div>
        )}
      </div>

      {resource.loading && !session && (
        <div role="status" className="flex items-center gap-2 text-sm text-slate-500 py-6 justify-center">
          <RefreshCw className="size-4 animate-spin text-emerald-600" />
          <span>Đang tải hướng dẫn chuyển khoản…</span>
        </div>
      )}

      {session && !stopped && (
        <>
          {/* Amount statistics breakdown (HomeStats style) */}
          <div className="grid grid-cols-3 gap-2.5 rounded-2xl bg-slate-50/80 p-3 sm:gap-3.5 sm:p-3.5 border border-slate-100">
            <div className="rounded-xl bg-white p-2.5 sm:p-3 text-center shadow-2xs border border-slate-100">
              <span className="block text-[11px] font-medium text-slate-500">Cần thanh toán</span>
              <strong className="mt-0.5 block text-xs sm:text-base font-bold text-slate-800">
                {money(amountNumber)}
              </strong>
            </div>
            <div className="rounded-xl bg-white p-2.5 sm:p-3 text-center shadow-2xs border border-slate-100">
              <span className="block text-[11px] font-medium text-slate-500">Đã nhận</span>
              <strong className="mt-0.5 block text-xs sm:text-base font-bold text-slate-600">
                {money(receivedNumber)}
              </strong>
            </div>
            <div className="rounded-xl bg-emerald-50/80 p-2.5 sm:p-3 text-center shadow-2xs border border-emerald-100">
              <span className="block text-[11px] font-medium text-emerald-700">Còn thiếu</span>
              <strong className="mt-0.5 block text-xs sm:text-base font-black text-emerald-600">
                {money(remainingNumber)}
              </strong>
            </div>
          </div>

          {/* Bank Transfer Details Box */}
          {session.state !== 'EXPIRED' && (
          <div className="space-y-3 rounded-2xl border border-slate-200/80 bg-slate-50/60 p-4 sm:p-5">
            <div className="flex items-center gap-2 pb-2 border-b border-slate-200/60 text-xs font-bold uppercase tracking-wider text-slate-600">
              <Building2 className="size-4 text-emerald-600" />
              <span>Thông tin tài khoản nhận</span>
            </div>

            <dl className="grid gap-3 text-sm">
              {/* Bank name */}
              <div className="flex items-center justify-between gap-3">
                <dt className="text-xs text-slate-500">Tên ngân hàng</dt>
                <dd className="font-semibold text-slate-900 text-right">
                  {bank?.shortName || bank?.name || (catalog.loading ? 'Đang tải…' : 'Chưa có tên ngân hàng')}
                </dd>
              </div>

              {/* Account number with Copy button */}
              {session.accountNumber && (
                <div className="flex items-center justify-between gap-3">
                  <dt className="text-xs text-slate-500">Số tài khoản cơ sở</dt>
                  <dd className="flex items-center gap-2">
                    <span className="font-mono font-bold tracking-wide text-slate-900">
                      {session.accountNumber}
                    </span>
                    <button
                      type="button"
                      onClick={() => copy(session.accountNumber, 'acc')}
                      className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-0.5 text-[11px] font-medium text-slate-600 shadow-2xs hover:bg-slate-50 active:scale-95 transition-all"
                    >
                      {copiedKey === 'acc' ? (
                        <>
                          <Check className="size-3 text-emerald-600" />
                          <span className="text-emerald-700 font-semibold">Đã chép</span>
                        </>
                      ) : (
                        <>
                          <Copy className="size-3" />
                          <span>Sao chép</span>
                        </>
                      )}
                    </button>
                  </dd>
                </div>
              )}

              {/* Account holder */}
              {session.accountName && (
                <div className="flex items-center justify-between gap-3">
                  <dt className="text-xs text-slate-500">Người nhận</dt>
                  <dd className="font-bold uppercase text-slate-900 text-right">
                    {session.accountName}
                  </dd>
                </div>
              )}

              {/* Transfer memo with Copy button */}
              <div className="flex items-center justify-between gap-3 pt-1 border-t border-slate-200/60">
                <dt className="text-xs text-slate-500">Nội dung chuyển khoản</dt>
                <dd className="flex items-center gap-2">
                  <span className="rounded-md bg-emerald-100/70 px-2 py-0.5 font-mono text-sm font-black text-emerald-900 border border-emerald-300/60">
                    {session.description}
                  </span>
                  <button
                    type="button"
                    onClick={() => copy(session.description, 'memo')}
                    className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-0.5 text-[11px] font-medium text-slate-600 shadow-2xs hover:bg-slate-50 active:scale-95 transition-all"
                  >
                    {copiedKey === 'memo' ? (
                      <>
                        <Check className="size-3 text-emerald-600" />
                        <span className="text-emerald-700 font-semibold">Đã chép</span>
                      </>
                    ) : (
                      <>
                        <Copy className="size-3" />
                        <span>Sao chép</span>
                      </>
                    )}
                  </button>
                </dd>
              </div>
            </dl>
          </div>
          )}

          {/* QR Code Container */}
          {showQr && (
            <div className="flex flex-col items-center justify-center rounded-2xl border border-slate-200/80 bg-slate-50/50 p-4 sm:p-6 text-center space-y-3.5">
              <div className="relative overflow-hidden rounded-2xl border-2 border-slate-200/90 bg-white p-3.5 shadow-md shadow-slate-900/5">
                {image.data && (
                  <Image
                    unoptimized
                    src={image.data}
                    width={300}
                    height={300}
                    alt="QR chuyển khoản thanh toán cho cơ sở"
                    className="size-auto max-w-[260px] sm:max-w-[280px]"
                  />
                )}
                {(image.error || image.loading) && (
                  <div role="status" className="p-8 text-xs text-slate-500">
                    {image.error || 'Đang dựng ảnh QR…'}{' '}
                    {image.error && (
                      <button className="text-emerald-700 underline font-semibold" onClick={image.reload}>
                        Tải lại ảnh QR
                      </button>
                    )}
                  </div>
                )}
              </div>

              <div className="space-y-2">
                <Countdown
                  expiresAt={session.expiresAt}
                  className="inline-flex items-center rounded-full px-3.5 py-1 text-xs font-semibold border border-amber-200"
                />
                <p className="flex items-center justify-center gap-1.5 text-xs text-slate-500">
                  <ShieldCheck className="size-4 text-emerald-600 shrink-0" />
                  <span>Giữ nguyên nội dung và số tiền khi chuyển để xác nhận tự động.</span>
                </p>
              </div>
            </div>
          )}

          {session.state === 'READY' && remaining !== null && remaining > 0 && (
            <>
              {!session.qrCode && (
                <div role="status" className="rounded-xl bg-amber-50 border border-amber-200 p-3 text-xs text-amber-800">
                  QR đang được khôi phục. Vui lòng chờ trong giây lát rồi tải lại.
                </div>
              )}
            </>
          )}

          {session.state === 'PARTIAL' && (
            <div className="flex items-start gap-2.5 rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-xs text-amber-800">
              <AlertCircle className="size-4 shrink-0 mt-0.5 text-amber-600" />
              <p>
                Chưa đủ tiền để sử dụng sân. Kiểm tra giao dịch trước khi chuyển phần còn thiếu; không chuyển lại toàn bộ.
              </p>
            </div>
          )}

          {session.state === 'VERIFIED' && !applied && !applicationNotice && (
            <div className="flex items-start gap-2.5 rounded-xl border border-emerald-200 bg-emerald-50 p-3.5 text-xs text-emerald-800">
              <CheckCircle2 className="size-4 shrink-0 mt-0.5 text-emerald-600" />
              <p>
                Đang áp dụng thanh toán vào lượt đặt. Quyền sử dụng được cấp khi hệ thống xác nhận đủ tiền và ghi nhận lượt đặt thành công.
              </p>
            </div>
          )}

          {applicationNotice && (
            <div role="status" className="flex items-start gap-2.5 rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-xs text-amber-800">
              <AlertCircle className="size-4 shrink-0 mt-0.5 text-amber-600" />
              <p>{applicationNotice}</p>
            </div>
          )}

          {['EXPIRED', 'REVIEW'].includes(session.state) && (
            <div className="flex items-start gap-2.5 rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-xs text-amber-800">
              <AlertCircle className="size-4 shrink-0 mt-0.5 text-amber-600" />
              <p>
                {session.state === 'EXPIRED' && payment.purpose === 'GROUP_CONTRIBUTION'
                  ? 'QR này đã hết hạn hoặc người tổ chức đã thanh toán thay phần của bạn. Đừng chuyển khoản; nếu đã chuyển, hãy liên hệ cơ sở để đối soát.'
                  : 'Không chuyển thêm theo QR cũ. Nếu đã chuyển, liên hệ cơ sở đối soát; tiền không áp dụng sẽ được ghi khoản cần hoàn.'}
              </p>
            </div>
          )}
        </>
      )}

      {(error || resource.error) && (
        <div role="alert" className="flex items-start gap-2.5 rounded-xl border border-red-200 bg-red-50 p-3.5 text-xs text-red-700">
          <AlertCircle className="size-4 shrink-0 mt-0.5 text-red-600" />
          <p>{error || resource.error}</p>
        </div>
      )}

      {timedOut && (
        <div role="status" className="rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-xs text-amber-800">
          Đã dừng theo dõi tại màn hình này. Kết quả vẫn được máy chủ đối soát; tải lại giao dịch để kiểm tra.
        </div>
      )}

      {/* Action buttons */}
      <div className="flex flex-col sm:flex-row gap-3 pt-2">
        <Button
          onClick={refresh}
          disabled={busy || stopped}
          className="h-11 flex-1 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-sm shadow-xs hover:shadow-md transition-all active:scale-[0.99] flex items-center justify-center gap-2"
        >
          <RefreshCw className={`size-4 ${busy ? 'animate-spin' : ''}`} />
          <span>{busy ? 'Đang kiểm tra…' : 'Kiểm tra giao dịch'}</span>
        </Button>
        {!onApplied && result && (
          <Link
            href={result}
            className="h-11 flex-1 rounded-xl border border-slate-200 bg-white hover:bg-slate-50 text-slate-700 hover:text-slate-900 font-semibold text-sm transition-all flex items-center justify-center gap-2 shadow-2xs"
          >
            <span>{applied ? 'Xem lượt đặt đã xác nhận' : 'Xem trạng thái lượt đặt'}</span>
            <ArrowRight className="size-4 text-slate-400" />
          </Link>
        )}
      </div>
    </section>
  )
}
