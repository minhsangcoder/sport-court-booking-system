'use client'
import { displayLabel } from '@/lib/display-labels'
import Image from 'next/image'
import Link from 'next/link'
import {
  ArrowLeftRight,
  CalendarClock,
  CircleAlert,
  History,
  Info,
  KeyRound,
  MapPin,
  Navigation,
  QrCode,
  Receipt,
  RefreshCw,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useAuth } from '@/lib/auth-context'
import { RequireAuth } from '@/features/auth/require-auth'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import { PageBanner } from '@/components/page-banner'
import { CourtLines } from '@/features/home/home-art'
import type { Facility, Court } from '@/features/facility/types'
import { type BookingDetail, type Payment, statusLabels } from './types'
import { Countdown } from './countdown'
import { operationsWorkspace, ownsBooking } from './ownership'
export function BookingDetailPage({ id }: { id: string }) {
  return (
    <RequireAuth>
      <Loaded id={id} />
    </RequireAuth>
  )
}
function Loaded({ id }: { id: string }) {
  const router = useRouter()
  const { user } = useAuth()
  const resource = useApi<BookingDetail>(`/bookings/${id}`)
  const [paymentKey] = useState(() => crypto.randomUUID())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const b = resource.data?.booking
  const facility = useApi<Facility>(b ? `/facilities/${b.facilityId}` : null)
  const courts = useApi<Court[]>(b ? `/facilities/${b.facilityId}/courts` : null)
  const owned = useApi<{ id: string }[]>(user?.roles.includes('OWNER') ? '/owner/facilities' : null)
  useEffect(() => {
    if (b?.status !== 'CONFIRMED') return
    const timer = setInterval(resource.reload, 60000)
    return () => clearInterval(timer)
  }, [b?.status, resource.reload])
  async function pay() {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      const p = await api<Payment>('/payments', {
        method: 'POST',
        headers: { 'Idempotency-Key': paymentKey },
        body: JSON.stringify({ bookingId: id }),
      })
      router.push(`/customer/payments/${p.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể tạo thanh toán.')
    } finally {
      setBusy(false)
    }
  }
  async function cancel() {
    if (!window.confirm('Hủy lượt giữ chỗ chưa thanh toán và giải phóng khung giờ?')) return
    setBusy(true)
    setError('')
    try {
      await api(`/bookings/${id}/cancel`, {
        method: 'POST',
        body: JSON.stringify({ reason: 'Khách hủy lượt giữ chỗ chưa thanh toán' }),
      })
      resource.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể hủy giữ chỗ.')
    } finally {
      setBusy(false)
    }
  }
  if (resource.loading && !resource.data)
    return (
      <p role="status" className="p-8">
        Đang tải lượt đặt…
      </p>
    )
  if (resource.error || !b)
    return (
      <p role="alert" className="p-8 text-red-700">
        {resource.error}
      </p>
    )
  const detail = resource.data!
  const timezone = b.priceSnapshot.timezone
  const isOwner = ownsBooking(b, user)
  const workspace = operationsWorkspace(
    user,
    b.facilityId,
    owned.data?.map((f) => f.id),
  )
  const isTransferred =
    detail.history.some((h) => h.action === 'TRANSFER_COMPLETED') ||
    Boolean(b.currentHolderId && b.customerId && b.currentHolderId !== b.customerId)
  const backHref = !isOwner && workspace ? `/${workspace}/bookings` : '/customer/bookings'
  const showCode = Boolean(b.bookingCode && isOwner)
  const showQr = Boolean(detail.checkinToken && isOwner)
  return (
    <div>
      <PageBanner
        icon="shuttle"
        eyebrow={
          <Link className="transition hover:text-emerald-200" href={backHref}>
            Lịch đặt / {id.slice(0, 8).toUpperCase()}
          </Link>
        }
        title={statusLabels[b.status] || displayLabel(b.status)}
        actions={
          <span className="home-rise inline-flex h-10 items-center rounded-full border border-white/25 bg-white/10 px-5 text-sm font-bold text-emerald-200 backdrop-blur-md [animation-delay:180ms]">
            {b.currency} · {money(b.amount, b.currency)}
          </span>
        }
      />
      <div
        className={`mx-auto grid max-w-6xl items-start gap-6 px-4 py-7 sm:px-6 sm:py-9 ${
          showCode || showQr ? 'lg:grid-cols-[minmax(0,1fr)_360px]' : ''
        }`}
      >
        <div className="min-w-0 space-y-6">
          <section className={`${card} home-rise p-5 sm:p-7`}>
            <CourtLines className="pointer-events-none absolute -right-20 -top-12 w-80 -rotate-6 text-emerald-600/[0.05]" />
            <h2 className="text-xl font-extrabold tracking-tight text-slate-950 sm:text-2xl dark:text-white">
              {facility.data?.name || 'Cơ sở'} ·{' '}
              {courts.data?.find((c) => c.id === b.courtId)?.name || 'Sân'}
            </h2>
            <p className="mt-4 inline-flex flex-wrap items-center gap-2 rounded-full bg-emerald-50 px-3.5 py-1.5 text-sm font-semibold text-emerald-800 dark:bg-emerald-500/10 dark:text-emerald-300">
              <CalendarClock className="size-4 shrink-0" />
              {new Date(b.startsAt).toLocaleString('vi-VN', { timeZone: timezone })} →{' '}
              {new Date(b.endsAt).toLocaleTimeString('vi-VN', { timeZone: timezone })}
            </p>
            <p className="mt-3 break-all font-mono text-[11px] text-slate-400">Mã lượt đặt {id}</p>
            {facility.data && (
              <div className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200/80 bg-slate-50/80 p-3.5 text-sm dark:border-slate-800 dark:bg-slate-800/50">
                <div className="flex items-center gap-2.5 text-slate-600 dark:text-slate-300">
                  <span className="grid size-8 shrink-0 place-items-center rounded-full bg-white text-emerald-600 shadow-2xs dark:bg-slate-900">
                    <MapPin className="size-4" />
                  </span>
                  <span>
                    {facility.data.addressLine}, {facility.data.ward}, {facility.data.province}
                  </span>
                </div>
                <Link
                  href={`/facilities/${facility.data.id}/directions?bookingId=${id}`}
                  className="inline-flex h-9 items-center gap-1.5 rounded-full border border-emerald-600/25 bg-white px-4 text-xs font-bold text-emerald-700 shadow-2xs transition hover:-translate-y-0.5 hover:border-emerald-600/50 hover:shadow-md dark:bg-slate-900 dark:text-emerald-300"
                >
                  <Navigation className="size-3.5" />
                  <span>Chỉ đường</span>
                </Link>
              </div>
            )}
            <div className="mt-5 space-y-2.5 border-t border-dashed border-slate-200 pt-4 dark:border-slate-800">
              {b.priceSnapshot.segments.map((s) => (
                <div className="flex items-center justify-between gap-3 text-sm" key={s.startsAt}>
                  <span className="inline-flex items-center gap-2 text-slate-600 dark:text-slate-300">
                    <Receipt className="size-4 text-slate-400" />
                    {s.ruleLabel}
                  </span>
                  <span className="font-semibold tabular-nums text-slate-900 dark:text-white">
                    {money(s.amount || 0, s.currency)}
                  </span>
                </div>
              ))}
            </div>
          </section>
          {b.status === 'CONFIRMED' && isOwner && (
            <div className="home-rise space-y-4 [animation-delay:80ms]">
              {b.priceSnapshot.moneyClass === 'REAL' && !resource.data?.resaleConsumed && (
                <Link
                  href={`/customer/bookings/${id}/transfer`}
                  className="inline-flex h-11 items-center gap-2 rounded-full bg-emerald-600 px-6 text-sm font-bold text-white shadow-[0_8px_24px_-6px_rgba(16,185,129,0.55)] transition hover:-translate-y-0.5 hover:bg-emerald-500"
                >
                  <ArrowLeftRight className="size-4" />
                  Đăng tin chuyển nhượng trực tiếp
                </Link>
              )}
              {resource.data?.resaleConsumed && (
                <p className="text-sm text-slate-600 dark:text-slate-300">
                  Lượt đặt đã chuyển nhượng một lần, không thể đăng bán tiếp.
                </p>
              )}
              {isTransferred ? (
                <p className="flex items-start gap-2.5 rounded-2xl border border-slate-200/80 bg-white/80 p-4 text-sm text-slate-600 shadow-2xs dark:border-slate-800 dark:bg-slate-900/60 dark:text-slate-300">
                  <Info className="mt-0.5 size-4 shrink-0 text-slate-400" />
                  Lượt đặt nhận qua chuyển nhượng không áp dụng hoàn tiền khi tự hủy.
                </p>
              ) : (
                <section className="relative overflow-hidden rounded-3xl border border-amber-200/80 bg-amber-50/70 p-5 sm:p-6 dark:border-amber-500/20 dark:bg-amber-500/5">
                  <h3 className="flex items-center gap-2 font-bold text-amber-950 dark:text-amber-200">
                    <CircleAlert className="size-4 shrink-0 text-amber-600" />
                    Tùy chọn khi không thể tham gia
                  </h3>
                  <p className="mt-2.5 text-sm leading-relaxed text-amber-950/80 dark:text-amber-100/80">
                    Theo chính sách cơ sở,{' '}
                    <strong>người đặt trực tiếp không áp dụng hoàn tiền khi tự hủy</strong>. Quý
                    khách có thể đăng chuyển nhượng để sang nhượng lại cho người khác trên hệ thống.
                    Với chuyển nhượng trực tiếp thành công, bạn nhận 100% giá chuyển nhượng đã chốt
                    từ người mua; nếu không chuyển nhượng được và không đến sân, bạn mất toàn bộ
                    tiền đã trả.
                  </p>
                  <div className="flex flex-wrap items-center gap-3 pt-1">
                    {b.priceSnapshot.moneyClass !== 'REAL' && (
                      <p className="text-sm text-amber-950/80 dark:text-amber-100/80">
                        Đây là lượt đặt mô phỏng cũ, không dùng để tạo chuyển nhượng tiền thật.
                      </p>
                    )}
                  </div>
                </section>
              )}
            </div>
          )}
          {b.status === 'PENDING' && isOwner && (
            <section className={`${card} home-rise space-y-4 p-5 sm:p-6 [animation-delay:80ms]`}>
              <Countdown expiresAt={b.holdExpiresAt} />
              <div className="flex flex-wrap gap-3">
                <Button
                  onClick={pay}
                  disabled={busy}
                  className="h-11 rounded-full bg-emerald-600 px-6 font-bold text-white shadow-[0_8px_24px_-6px_rgba(16,185,129,0.55)] transition hover:-translate-y-0.5 hover:bg-emerald-500"
                >
                  Thanh toán
                </Button>
                <Button
                  onClick={cancel}
                  disabled={busy}
                  variant="outline"
                  className="h-11 rounded-full px-6"
                >
                  Hủy giữ chỗ
                </Button>
              </div>
            </section>
          )}
          {error && (
            <p
              role="alert"
              className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
            >
              {error}
            </p>
          )}
        </div>
        {(showCode || showQr) && (
          <aside className="home-rise space-y-4 [animation-delay:120ms] lg:sticky lg:top-24">
            {showCode && (
              <p className={`${card} flex flex-wrap items-center gap-x-2 gap-y-1 p-4 text-sm`}>
                <KeyRound className="size-4 text-emerald-600" />
                Mã xác nhận vào sân thủ công:{' '}
                <strong className="font-mono text-base tracking-[0.18em] text-slate-950 dark:text-white">
                  {b.bookingCode}
                </strong>
              </p>
            )}
            {showQr && (
              <section className="relative isolate overflow-hidden rounded-3xl bg-gradient-to-br from-emerald-600 via-emerald-700 to-teal-800 p-6 text-white shadow-xl shadow-emerald-900/20">
                <CourtLines className="pointer-events-none absolute -right-16 -top-10 -z-10 w-72 rotate-6 text-white/10" />
                <h2 className="flex items-center gap-2 text-xl font-extrabold tracking-tight">
                  <QrCode className="size-5 text-emerald-200" />
                  Mã xác nhận vào sân của bạn
                </h2>
                <p className="mt-2 text-sm text-emerald-100/90">
                  Mã tự hết hạn sau 2 phút. Mở trang khi đến sân để nhận mã mới.
                </p>
                {detail.checkinQrSvg && (
                  <Image
                    unoptimized
                    src={`data:image/svg+xml,${encodeURIComponent(detail.checkinQrSvg)}`}
                    width={240}
                    height={240}
                    alt="QR xác nhận vào sân lượt đặt"
                    className="mx-auto mt-5 rounded-2xl bg-white p-3 shadow-lg ring-4 ring-white/20"
                  />
                )}
                <button
                  onClick={resource.reload}
                  className="mx-auto mt-5 flex h-10 items-center gap-1.5 rounded-full border border-white/30 bg-white/10 px-5 text-sm font-semibold text-white backdrop-blur-md transition hover:bg-white/20"
                >
                  <RefreshCw className="size-4" />
                  Làm mới mã
                </button>
              </section>
            )}
          </aside>
        )}
        <section
          className={`${card} home-rise p-5 sm:p-7 [animation-delay:160ms] ${
            showCode || showQr ? 'lg:col-span-2' : ''
          }`}
        >
          <h2 className="flex items-center gap-2 text-xl font-extrabold tracking-tight text-slate-950 dark:text-white">
            <History className="size-5 text-emerald-600" />
            Lịch sử lượt đặt
          </h2>
          <ol className="mt-5 space-y-5 border-l-2 border-emerald-100 pl-6 dark:border-emerald-500/20">
            {detail.history.map((h) => (
              <li key={h.id} className="relative">
                <span className="absolute -left-[31px] top-1 size-3 rounded-full bg-emerald-500 ring-4 ring-emerald-100 dark:ring-emerald-500/20" />
                <p className="text-sm font-semibold text-slate-900 dark:text-white">
                  {statusLabels[h.action] || displayLabel(h.action, 'Đã cập nhật lượt đặt')}
                </p>
                <p className="mt-1 text-xs text-slate-500">
                  {new Date(h.createdAt).toLocaleString('vi-VN')}
                </p>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </div>
  )
}

const card =
  'relative isolate overflow-hidden rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900'
