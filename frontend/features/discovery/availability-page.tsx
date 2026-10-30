'use client'
import { timezoneLabel, displayLabel } from '@/lib/display-labels'
import Link from 'next/link'
import { PageBanner, bannerGhost } from '@/components/page-banner'
import { Navigation, CalendarClock, Clock3, RefreshCw } from 'lucide-react'
import { useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useApi } from '@/lib/use-api'
import { api, ApiError } from '@/lib/api'
import { AdmissionNotice, AdmissionErrorContext } from '@/features/booking/admission-notice'
import { useAuth } from '@/lib/auth-context'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import type { Facility, Court } from '@/features/facility/types'
import { OwnFacilityNotice } from '@/features/booking/own-facility-notice'
import { type Preview, type Slot, money } from '@/features/schedule/types'
import type { Hold } from '@/features/booking/types'
import { useIntervalQuote } from './use-interval-quote'
import { availableRanges, rangeSelection } from './available-ranges'
import { usePolling } from '@/lib/use-polling'
const reasons: Record<string, string> = {
  MAINTENANCE: 'Bảo trì',
  CLOSED_EXCEPTION: 'Đóng cửa',
  PRICE_NOT_CONFIGURED: 'Chưa có giá',
  AMBIGUOUS_PRICE: 'Giá cần được kiểm tra',
  COURT_DISABLED: 'Sân tạm ngừng',
  PAST_SLOT: 'Đã qua giờ',
  HELD: 'Đang giữ chỗ',
  BOOKED: 'Đã được đặt',
}
export function AvailabilityPage({
  facilityId,
  courtId,
  counter = false,
  initialDate,
}: {
  facilityId: string
  courtId: string
  counter?: boolean
  initialDate?: string
}) {
  const router = useRouter()
  const { user } = useAuth()
  const facility = useApi<Facility>(`/facilities/${facilityId}`)
  const courts = useApi<Court[]>(`/facilities/${facilityId}/courts`)
  const ownFacility = !counter && Boolean(user && facility.data?.ownerId === user.id)
  const [date, setDate] = useState(initialDate ?? new Date().toLocaleDateString('en-CA'))
  const resource = useApi<Preview>(`/bookings/availability?courtId=${courtId}&date=${date}`)
  const [selection, setSelection] = useState<Slot[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [policyFailure, setPolicyFailure] = useState<ApiError | null>(null)
  const court = courts.data?.find((c) => c.id === courtId)
  const slots = resource.data?.slots ?? []
  const ranges = availableRanges(slots)
  const unavailable = selection.some(
    (s) =>
      !slots.some(
        (current) =>
          current.startsAt === s.startsAt &&
          current.endsAt === s.endsAt &&
          current.state === 'AVAILABLE',
      ),
  )
  usePolling((signal) => resource.poll(signal), {
    interval: 5000,
    enabled: !busy,
    scope: `${courtId}:${date}`,
  })
  const pricing = useIntervalQuote(
    courtId,
    unavailable ? [] : selection,
    resource.data?.pricingMode !== 'LEGACY_SLOT',
  )
  const holdIntent = useRef<{ selection: string; payload: string; key: string } | null>(null)
  function chooseRange(startsAt: string, endsAt: string) {
    holdIntent.current = null
    setError('')
    setSelection(rangeSelection(slots, startsAt, endsAt))
  }
  const start = selection[0]?.startsAt ?? ''
  const end = selection[selection.length - 1]?.endsAt ?? ''
  const startOptions = slots.filter(
    (s) =>
      s.state === 'AVAILABLE' &&
      ranges.some(
        (r) => s.startsAt >= r.startsAt && Date.parse(s.startsAt) + 1800000 <= Date.parse(r.endsAt),
      ),
  )
  const endOptions = start
    ? slots.filter((s) => {
        const minutes = (Date.parse(s.endsAt) - Date.parse(start)) / 60000
        return minutes >= 30 && rangeSelection(slots, start, s.endsAt).length > 0
      })
    : []
  function select(slot: Slot) {
    holdIntent.current = null
    setError('')
    setSelection((current) =>
      current.some((s) => s.startsAt === slot.startsAt)
        ? current.filter((s) => s.startsAt !== slot.startsAt)
        : [...current, slot].sort((a, b) => a.startsAt.localeCompare(b.startsAt)),
    )
  }
  async function hold() {
    if (busy || ownFacility || !facility.data || !pricing.interval || !pricing.quote) return
    if (!user) {
      router.push(
        `/login?next=${encodeURIComponent(`/facilities/${facilityId}/courts/${courtId}`)}`,
      )
      return
    }
    setBusy(true)
    setError('')
    setPolicyFailure(null)
    try {
      const interval = pricing.interval
      const fingerprint = JSON.stringify({ ...interval, counter })
      if (holdIntent.current?.selection !== fingerprint) {
        const quote = pricing.quote
        holdIntent.current = {
          selection: fingerprint,
          payload: JSON.stringify({
            ...interval,
            expectedAmount: quote.amount,
            ...(quote.quoteHash ? { quoteHash: quote.quoteHash } : {}),
          }),
          key: crypto.randomUUID(),
        }
      }
      const body = holdIntent.current.payload
      const h = await api<Hold>(`/bookings/holds${counter ? '?counter=true' : ''}`, {
        method: 'POST',
        headers: { 'Idempotency-Key': holdIntent.current.key },
        body,
      })
      router.push(`/customer/booking/holds/${h.id}${counter ? '?counter=true' : ''}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể giữ chỗ.')
      setPolicyFailure(err instanceof ApiError ? err : null)
    } finally {
      setBusy(false)
    }
  }
  const timezone = facility.data?.timezone || resource.data?.timezone
  const time = (v: string) =>
    new Date(v).toLocaleTimeString('vi-VN', {
      timeZone: timezone,
      hour: '2-digit',
      minute: '2-digit',
    })
  return (
    <div>
      <PageBanner
        eyebrow={counter ? 'Vận hành · Đặt tại quầy' : 'Đặt sân · Chọn giờ chơi'}
        title={court?.name || 'Chọn giờ chơi'}
        description={
          counter
            ? `${facility.data?.name || 'Cơ sở'} · Đặt trực tiếp cho khách tại quầy.`
            : facility.data?.name || 'Tìm khoảng giờ phù hợp cho buổi chơi của bạn.'
        }
        icon="shuttle"
        actions={
          counter ? (
            <Link
              href={`/facilities/${facilityId}/directions`}
              className={bannerGhost}
            >
              <Navigation size={16} /> Chỉ đường đến sân
            </Link>
          ) : (
            <Link href={`/facilities/${facilityId}/directions`} className={bannerGhost}>
              <Navigation size={16} /> Chỉ đường đến sân
            </Link>
          )
        }
      >
        <div className="flex flex-wrap gap-2.5 text-xs text-emerald-100">
          <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-400/40 bg-emerald-500/25 px-3.5 py-1.5 font-semibold text-emerald-200 shadow-xs backdrop-blur-md">
            <span className="size-1.5 rounded-full bg-emerald-400" />
            01 · Chọn giờ
          </span>
          <span className="rounded-full border border-white/10 bg-white/5 px-3.5 py-1.5 text-slate-300 backdrop-blur-md">
            02 · Kiểm tra lượt đặt
          </span>
          <span className="rounded-full border border-white/10 bg-white/5 px-3.5 py-1.5 text-slate-300 backdrop-blur-md">
            03 · {counter ? 'Thanh toán tại quầy' : 'Thanh toán'}
          </span>
        </div>
      </PageBanner>
      <div className="home-rise mx-auto max-w-6xl space-y-6 px-4 py-7 sm:px-6 sm:py-9">
        {ownFacility && <OwnFacilityNotice facilityId={facilityId} />}
        {!counter && !ownFacility && (
          <>
            <AdmissionNotice />
            <AdmissionErrorContext failure={policyFailure} />
          </>
        )}

        {/* Court info & metadata banner */}
        <div className="rounded-2xl border border-slate-200/80 bg-white/90 p-5 shadow-xs backdrop-blur-sm dark:border-slate-800 dark:bg-slate-900/90">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-base font-bold text-slate-900 dark:text-white">
                {court?.name || 'Sân'} {court?.code ? `(${court.code})` : ''} · {facility.data?.name}
              </p>
              <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                Lịch trống cập nhật từ hệ thống. Chọn khung giờ chưa giữ chỗ; giờ theo{' '}
                {timezoneLabel(timezone || 'cơ sở')}.
              </p>
            </div>
            {resource.data?.selectionStepMinutes === 30 && (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/20 bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700 dark:border-emerald-500/30 dark:bg-emerald-950/40 dark:text-emerald-300">
                <span className="size-1.5 rounded-full bg-emerald-500 animate-pulse" />
                Bước chọn 30 phút · Tối thiểu 30 phút
              </span>
            )}
          </div>
          {resource.data?.selectionStepMinutes === 30 && (
            <p className="mt-2.5 border-t border-slate-100 pt-2.5 text-xs text-slate-500 dark:border-slate-800 dark:text-slate-400">
              Chọn liên tiếp từ 30 phút trở lên, theo bước 30 phút. Giá cuối tính theo toàn bộ
              khoảng chọn và làm tròn một lần.
            </p>
          )}
        </div>

        {!counter && !ownFacility && (
          <aside className="space-y-1.5 rounded-2xl border border-slate-200/80 bg-gradient-to-br from-slate-50/90 via-white to-amber-50/20 p-4 sm:p-5 text-xs text-slate-700 shadow-2xs dark:border-slate-800 dark:from-slate-900/90 dark:to-slate-900/40 dark:text-slate-300">
            <div className="flex items-center gap-2">
              <span className="inline-flex size-6 items-center justify-center rounded-full bg-amber-100 text-amber-700 text-xs font-bold dark:bg-amber-950/50 dark:text-amber-300">
                !
              </span>
              <p className="font-bold text-slate-900 dark:text-white">
                Lượt đặt trực tiếp không được hoàn tiền khi khách tự hủy.
              </p>
            </div>
            <p className="mt-1 leading-relaxed pl-8">
              Nếu không thể đến sân, bạn có thể đăng chuyển nhượng. Khi chuyển nhượng trực tiếp
              thành công, bạn nhận <strong>100% giá chuyển nhượng đã chốt</strong> từ người mua,
              không trừ phí. Nếu không chuyển nhượng được và không sử dụng sân, bạn mất toàn bộ tiền
              đã trả.
            </p>
          </aside>
        )}

        {/* Date picker */}
        <div className="flex flex-wrap items-end justify-between gap-4 rounded-2xl border border-slate-200/80 bg-white/90 p-5 shadow-xs backdrop-blur-sm dark:border-slate-800 dark:bg-slate-900/90">
          <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
            <span className="flex items-center gap-2">
              <CalendarClock size={16} className="text-emerald-600 dark:text-emerald-400" /> Ngày chơi
            </span>
            <Input
              type="date"
              value={date}
              min={new Date().toLocaleDateString('en-CA')}
              onChange={(e) => {
                setDate(e.target.value)
                setSelection([])
                holdIntent.current = null
              }}
              required
              disabled={busy}
              className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
            />
          </label>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              resource.reload()
              setSelection([])
              holdIntent.current = null
            }}
            disabled={busy}
            className="h-11 rounded-xl border border-slate-200 bg-white px-4 font-semibold text-slate-700 shadow-2xs hover:bg-slate-50 hover:border-slate-300 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <RefreshCw size={14} className={resource.loading ? 'animate-spin' : ''} /> Làm mới lịch
          </Button>
        </div>

        {/* Available ranges */}
        <section
          aria-label="Lịch trống"
          className="relative overflow-hidden rounded-2xl border border-emerald-200/80 bg-gradient-to-br from-emerald-50/70 via-white to-teal-50/40 p-5 shadow-xs sm:p-6 dark:border-emerald-900/50 dark:from-emerald-950/20 dark:via-slate-900 dark:to-slate-900"
        >
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="flex items-center gap-2 text-base font-bold text-slate-900 dark:text-white">
                <Clock3 size={18} className="text-emerald-600 dark:text-emerald-400" /> Các khoảng còn trống
              </h2>
              <p className="mt-1 text-xs text-slate-600 dark:text-slate-400">
                Tự cập nhật mỗi 5 giây. Chọn một khoảng hoặc tự chọn giờ bên dưới.
              </p>
            </div>
            <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/20 bg-emerald-100/70 px-2.5 py-1 text-[11px] font-bold text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-950/50 dark:text-emerald-300">
              <span className="relative flex size-2">
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-75"></span>
                <span className="relative inline-flex size-2 rounded-full bg-emerald-500"></span>
              </span>
              Tự cập nhật
            </span>
          </div>
          <div className="mt-4 flex flex-wrap gap-2.5">
            {ranges.map((range) => (
              <Button
                key={`range-${range.startsAt}`}
                variant="outline"
                size="sm"
                disabled={
                  busy ||
                  ownFacility ||
                  resource.loading ||
                  Date.parse(range.endsAt) - Date.parse(range.startsAt) < 3600000
                }
                onClick={() =>
                  chooseRange(
                    range.startsAt,
                    new Date(Date.parse(range.startsAt) + 3600000).toISOString(),
                  )
                }
                className="h-auto rounded-xl border border-emerald-300/80 bg-white/90 px-3.5 py-2 text-xs font-bold text-emerald-800 shadow-2xs transition-all duration-200 hover:border-emerald-600 hover:bg-emerald-600 hover:text-white hover:shadow-md active:scale-95 disabled:opacity-50 dark:border-emerald-800 dark:bg-slate-800 dark:text-emerald-300"
              >
                {time(range.startsAt)}–{time(range.endsAt)}
              </Button>
            ))}
          </div>
          {!resource.loading && !resource.error && ranges.length === 0 && (
            <p className="mt-3 text-xs font-medium text-slate-500 dark:text-slate-400">
              Không còn giờ trống trong ngày này. Hãy chọn ngày khác.
            </p>
          )}
        </section>

        {/* Manual time selection */}
        <section
          aria-label="Tự chọn giờ chơi"
          className="rounded-2xl border border-slate-200/80 bg-white/90 p-5 shadow-xs backdrop-blur-sm sm:p-6 dark:border-slate-800 dark:bg-slate-900/90"
        >
          <h2 className="text-sm font-bold uppercase tracking-wider text-slate-800 dark:text-slate-200">
            Tự chọn giờ chơi
          </h2>
          <div className="mt-3.5 grid gap-4 sm:grid-cols-2">
            <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
              Giờ bắt đầu
              <select
                className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                value={start}
                disabled={busy || ownFacility || resource.loading}
                onChange={(e) => {
                  if (!e.target.value) {
                    setSelection([])
                    return
                  }
                  chooseRange(
                    e.target.value,
                    new Date(Date.parse(e.target.value) + 3600000).toISOString(),
                  )
                }}
              >
                <option value="">Chọn giờ bắt đầu</option>
                {startOptions.map((s) => (
                  <option key={s.startsAt} value={s.startsAt}>
                    {time(s.startsAt)}
                  </option>
                ))}
              </select>
            </label>
            <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
              Giờ kết thúc
              <select
                className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                value={end}
                disabled={busy || ownFacility || resource.loading || !start}
                onChange={(e) => chooseRange(start, e.target.value)}
              >
                <option value="">Chọn giờ kết thúc</option>
                {endOptions.map((s) => (
                  <option key={s.endsAt} value={s.endsAt}>
                    {time(s.endsAt)}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <p className="mt-2.5 text-xs text-slate-500 dark:text-slate-400">
            Chọn từ 30 phút trở lên. Chỉ các khoảng còn trống liên tục mới có thể đặt.
          </p>
        </section>

        {(resource.error || facility.error || courts.error || error) && (
          <p
            role="alert"
            className="rounded-2xl border border-red-200 bg-red-50 p-4 text-xs font-medium text-red-700 shadow-2xs dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
          >
            {error || resource.error || facility.error || courts.error}
          </p>
        )}

        {resource.loading && (
          <p role="status" className="text-xs font-medium text-slate-500 dark:text-slate-400">
            Đang kiểm tra lịch trống…
          </p>
        )}

        {resource.data?.slots.length === 0 && (
          <p className="rounded-2xl border border-slate-200/80 bg-white p-6 text-center text-xs font-medium text-slate-500 shadow-2xs dark:border-slate-800 dark:bg-slate-900 dark:text-slate-400">
            Không có khung giờ mở cửa trong ngày này. Hãy chọn ngày khác.
          </p>
        )}

        {/* Slot Grid */}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {resource.data?.slots.map((s) => {
            const selected = selection.some((v) => v.startsAt === s.startsAt)
            return (
              <button
                key={s.startsAt}
                type="button"
                disabled={busy || ownFacility || resource.loading || s.state !== 'AVAILABLE'}
                onClick={() => select(s)}
                aria-pressed={selected}
                className={`group relative flex flex-col justify-between rounded-2xl p-4 text-left transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 motion-reduce:transition-none ${
                  selected
                    ? 'border-2 border-emerald-600 bg-gradient-to-br from-emerald-600 to-teal-700 text-white shadow-lg shadow-emerald-600/30 -translate-y-0.5 scale-[1.01] ring-2 ring-emerald-500/20'
                    : s.state === 'AVAILABLE'
                      ? 'border border-slate-200/90 bg-white shadow-2xs hover:border-emerald-500 hover:shadow-md hover:-translate-y-0.5 dark:border-slate-800 dark:bg-slate-900'
                      : 'border border-slate-100 bg-slate-50/60 text-slate-400 opacity-60 cursor-not-allowed dark:border-slate-800/50 dark:bg-slate-900/40 dark:text-slate-600'
                }`}
              >
                <div>
                  <span
                    className={`block text-xs sm:text-sm font-bold tracking-tight ${
                      selected
                        ? 'text-white'
                        : s.state === 'AVAILABLE'
                          ? 'text-slate-900 group-hover:text-emerald-700 dark:text-white dark:group-hover:text-emerald-400'
                          : 'text-slate-500 dark:text-slate-500'
                    }`}
                  >
                    {time(s.startsAt)}–{time(s.endsAt)}
                  </span>
                  <span
                    className={`mt-1.5 block text-xs font-semibold ${
                      selected
                        ? 'text-emerald-100'
                        : s.state === 'AVAILABLE'
                          ? 'text-emerald-600 dark:text-emerald-400'
                          : 'text-slate-400 dark:text-slate-600'
                    }`}
                  >
                    {s.amount == null ? 'Chưa có giá' : money(s.amount, s.currency)}
                  </span>
                </div>
                <div className="mt-3">
                  <span
                    className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-bold ${
                      selected
                        ? 'bg-white/20 text-white'
                        : s.state === 'AVAILABLE'
                          ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-300'
                          : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
                    }`}
                  >
                    {s.state === 'AVAILABLE' && !selected && (
                      <span className="size-1 rounded-full bg-emerald-500" />
                    )}
                    {s.reason
                      ? reasons[s.reason] || displayLabel(s.reason, 'Không thể đặt')
                      : 'Còn trống'}
                  </span>
                </div>
              </button>
            )
          })}
        </div>

        {pricing.quote?.warnings?.length ? (
          <div
            role="status"
            className="rounded-2xl border border-amber-200 bg-gradient-to-br from-amber-50 to-amber-100/30 p-4 text-xs text-amber-900 shadow-2xs dark:border-amber-900/50 dark:from-amber-950/30 dark:to-transparent dark:text-amber-200"
          >
            <p className="font-semibold">
              Khoảng chọn để lại giờ trống dưới 30 phút. Bạn vẫn có thể tiếp tục hoặc chọn gợi ý:
            </p>
            <div className="mt-2.5 flex flex-wrap gap-2">
              {pricing.quote.suggestions?.map((suggestion) => (
                <Button
                  key={suggestion.startsAt + suggestion.endsAt}
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    holdIntent.current = null
                    setSelection(
                      (resource.data?.slots ?? []).filter(
                        (slot) =>
                          slot.state === 'AVAILABLE' &&
                          slot.startsAt >= suggestion.startsAt &&
                          slot.endsAt <= suggestion.endsAt,
                      ),
                    )
                  }}
                  className="rounded-xl border-amber-300 bg-white font-semibold text-amber-900 hover:bg-amber-100 dark:border-amber-700 dark:bg-slate-800 dark:text-amber-200"
                >
                  {time(suggestion.startsAt)}–{time(suggestion.endsAt)}
                </Button>
              ))}
            </div>
          </div>
        ) : null}

        {/* Sticky hold summary bar */}
        {selection.length > 0 && (
          <aside className="sticky bottom-4 z-20 mt-8 flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-emerald-500/20 bg-white/95 p-4 sm:p-5 shadow-2xl backdrop-blur-xl dark:border-emerald-500/30 dark:bg-slate-900/95">
            <div>
              <p className="inline-flex items-center gap-1.5 rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                <span>{selection.length} khung giờ</span>
                <span>·</span>
                <span>
                  {time(selection[0].startsAt)}–{time(selection[selection.length - 1].endsAt)}
                </span>
              </p>
              <p className="mt-1 text-2xl font-black tracking-tight text-slate-900 dark:text-white" aria-live="polite">
                {pricing.loading
                  ? 'Đang tính giá…'
                  : pricing.quote
                    ? money(pricing.quote.amount)
                    : 'Chọn đủ thời lượng'}
              </p>
              {(pricing.error || unavailable) && (
                <p role="alert" className="mt-1 text-xs font-medium text-red-700 dark:text-red-400">
                  {unavailable
                    ? 'Khoảng đã chọn không còn trống. Vui lòng chọn lại giờ chơi.'
                    : pricing.error}
                </p>
              )}
            </div>
            <Button
              onClick={hold}
              disabled={
                busy ||
                ownFacility ||
                !facility.data ||
                resource.loading ||
                pricing.loading ||
                !pricing.quote
              }
              className="h-12 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 px-6 font-bold text-white shadow-lg shadow-emerald-600/25 transition-all hover:from-emerald-500 hover:to-teal-500 hover:scale-[1.02] active:scale-[0.98] disabled:opacity-50"
            >
              {busy ? 'Đang kiểm tra…' : user ? 'Xem tóm tắt và giữ chỗ' : 'Đăng nhập để đặt sân'}
            </Button>
          </aside>
        )}
      </div>
    </div>
  )
}
