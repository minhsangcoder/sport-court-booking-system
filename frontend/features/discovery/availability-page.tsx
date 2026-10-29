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
        (r) => s.startsAt >= r.startsAt && Date.parse(s.startsAt) + 3600000 <= Date.parse(r.endsAt),
      ),
  )
  const endOptions = start
    ? slots.filter((s) => {
        const minutes = (Date.parse(s.endsAt) - Date.parse(start)) / 60000
        return minutes >= 60 && minutes <= 240 && rangeSelection(slots, start, s.endsAt).length > 0
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
        eyebrow="Đặt sân · Chọn giờ chơi"
        title={court?.name || 'Chọn giờ chơi'}
        description={facility.data?.name || 'Tìm khoảng giờ phù hợp cho buổi chơi của bạn.'}
        icon="shuttle"
        actions={
          <Link href={`/facilities/${facilityId}/directions`} className={bannerGhost}>
            <Navigation size={16} /> Chỉ đường đến sân
          </Link>
        }
      >
        <div className="flex flex-wrap gap-3 text-xs text-emerald-100">
          <span className="rounded-full border border-white/15 bg-white/10 px-3 py-1.5">
            01 · Chọn giờ
          </span>
          <span className="px-3 py-1.5 text-slate-300">02 · Kiểm tra lượt đặt</span>
          <span className="px-3 py-1.5 text-slate-300">03 · Thanh toán</span>
        </div>
      </PageBanner>
      <div className="home-rise mx-auto max-w-6xl space-y-5 px-4 py-7 sm:px-6 sm:py-9">
        {ownFacility && <OwnFacilityNotice facilityId={facilityId} />}
        {!counter && !ownFacility && (
          <>
            <AdmissionNotice />
            <AdmissionErrorContext failure={policyFailure} />
          </>
        )}
        <p className="mt-1 text-sm font-semibold text-slate-700">
          {court?.name || 'Sân'} {court?.code ? `(${court.code})` : ''} · {facility.data?.name}
        </p>
        <p className="mt-2 text-xs text-slate-500">
          Lịch trống cập nhật từ hệ thống. Chọn khung giờ chưa giữ chỗ; giờ theo{' '}
          {timezoneLabel(timezone || 'cơ sở')}.
        </p>
        {resource.data?.selectionStepMinutes === 30 && (
          <p className="mt-1.5 text-xs text-slate-500">
            Chọn liên tiếp từ 60 phút đến 4 giờ, theo bước 30 phút. Giá cuối tính theo toàn bộ
            khoảng chọn và làm tròn một lần.
          </p>
        )}
        {!counter && !ownFacility && (
          <aside className="mt-4 space-y-1.5 rounded-xl border border-slate-200 bg-slate-50 p-3.5 text-xs text-slate-700">
            <p className="font-semibold text-slate-900">
              Lượt đặt trực tiếp không được hoàn tiền khi khách tự hủy.
            </p>
            <p className="leading-relaxed">
              Nếu không thể đến sân, bạn có thể đăng chuyển nhượng. Khi chuyển nhượng trực tiếp
              thành công, bạn nhận <strong>100% giá chuyển nhượng đã chốt</strong> từ người mua,
              không trừ phí. Nếu không chuyển nhượng được và không sử dụng sân, bạn mất toàn bộ tiền
              đã trả.
            </p>
          </aside>
        )}
        <div className="flex flex-wrap items-end justify-between gap-4 rounded-2xl border border-border bg-card p-5 shadow-sm">
          <label className="grid gap-1.5 text-xs font-medium text-slate-700">
            <span className="flex items-center gap-2">
              <CalendarClock size={16} className="text-primary" /> Ngày chơi
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
          >
            <RefreshCw size={14} /> Làm mới lịch
          </Button>
        </div>
        <section
          aria-label="Lịch trống"
          className="rounded-2xl border border-primary/20 bg-gradient-to-br from-accent to-card p-5 shadow-sm sm:p-6"
        >
          <h2 className="flex items-center gap-2 text-base font-bold text-foreground">
            <Clock3 size={18} className="text-primary" /> Các khoảng còn trống
          </h2>
          <p className="mt-0.5 text-xs text-slate-600">
            Tự cập nhật mỗi 5 giây. Chọn một khoảng hoặc tự chọn giờ bên dưới.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
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
              >
                {time(range.startsAt)}–{time(range.endsAt)}
              </Button>
            ))}
          </div>
          {!resource.loading && !resource.error && ranges.length === 0 && (
            <p className="mt-2.5 text-xs text-slate-600">
              Không còn giờ trống trong ngày này. Hãy chọn ngày khác.
            </p>
          )}
        </section>
        <section
          aria-label="Tự chọn giờ chơi"
          className="rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-6"
        >
          <h2 className="text-sm font-semibold text-slate-900">Tự chọn giờ chơi</h2>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <label className="grid gap-1.5 text-xs font-medium text-slate-700">
              Giờ bắt đầu
              <select
                className="h-11 rounded-xl border border-input bg-background px-3 text-sm text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
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
            <label className="grid gap-1.5 text-xs font-medium text-slate-700">
              Giờ kết thúc
              <select
                className="h-11 rounded-xl border border-input bg-background px-3 text-sm text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
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
          <p className="mt-2.5 text-xs text-slate-500">
            Chọn từ 60 phút đến 4 giờ. Chỉ các khoảng còn trống liên tục mới có thể đặt.
          </p>
        </section>
        {(resource.error || facility.error || courts.error || error) && (
          <p
            role="alert"
            className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3.5 text-xs text-red-700"
          >
            {error || resource.error || facility.error || courts.error}
          </p>
        )}
        {resource.loading && (
          <p role="status" className="text-xs text-slate-500">
            Đang kiểm tra lịch trống…
          </p>
        )}
        {resource.data?.slots.length === 0 && (
          <p className="rounded-xl border border-slate-200 bg-white p-5 text-xs text-slate-500">
            Không có khung giờ mở cửa trong ngày này. Hãy chọn ngày khác.
          </p>
        )}
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
                className={`rounded-2xl border p-4 text-left transition duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring motion-reduce:transition-none ${selected ? 'border-primary bg-primary text-primary-foreground shadow-md' : s.state === 'AVAILABLE' ? 'border-border bg-card shadow-sm hover:border-primary hover:shadow-md motion-safe:hover:-translate-y-0.5' : 'border-border/60 bg-muted/50 text-muted-foreground'}`}
              >
                <span className="block text-xs font-semibold">
                  {time(s.startsAt)}–{time(s.endsAt)}
                </span>
                <span className="mt-1 block text-xs font-medium">
                  {s.amount == null ? 'Chưa có giá' : money(s.amount, s.currency)}
                </span>
                <span className="mt-1 block text-[11px] opacity-80">
                  {s.reason
                    ? reasons[s.reason] || displayLabel(s.reason, 'Không thể đặt')
                    : 'Còn trống'}
                </span>
              </button>
            )
          })}
        </div>
        {pricing.quote?.warnings?.length ? (
          <div
            role="status"
            className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-xs"
          >
            <p>
              Khoảng chọn để lại giờ trống dưới 60 phút. Bạn vẫn có thể tiếp tục hoặc chọn gợi ý:
            </p>
            <div className="mt-2 flex flex-wrap gap-2">
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
                >
                  {time(suggestion.startsAt)}–{time(suggestion.endsAt)}
                </Button>
              ))}
            </div>
          </div>
        ) : null}
        {selection.length > 0 && (
          <aside className="sticky bottom-3 z-10 mt-6 flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-primary/20 bg-card/95 p-5 shadow-xl backdrop-blur-md">
            <div>
              <p className="text-xs text-slate-500">
                {selection.length} khung giờ · {time(selection[0].startsAt)}–
                {time(selection[selection.length - 1].endsAt)}
              </p>
              <p className="mt-0.5 text-lg font-bold text-slate-900" aria-live="polite">
                {pricing.loading
                  ? 'Đang tính giá…'
                  : pricing.quote
                    ? money(pricing.quote.amount)
                    : 'Chọn đủ thời lượng'}
              </p>
              {(pricing.error || unavailable) && (
                <p role="alert" className="mt-1 text-xs text-red-700">
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
              className="bg-emerald-700 px-5 text-white hover:bg-emerald-800"
            >
              {busy ? 'Đang kiểm tra…' : user ? 'Xem tóm tắt và giữ chỗ' : 'Đăng nhập để đặt sân'}
            </Button>
          </aside>
        )}
      </div>
    </div>
  )
}
