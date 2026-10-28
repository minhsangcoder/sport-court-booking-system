'use client'
import { timezoneLabel, displayLabel } from '@/lib/display-labels'
import Link from 'next/link'
import { Navigation } from 'lucide-react'
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
    <div className="mx-auto max-w-6xl px-5 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-slate-500">
        <Link className="hover:underline" href={`/facilities/${facilityId}`}>
          {facility.data?.name || 'Cơ sở'} / {court?.name || 'Sân'}
        </Link>
        <Link
          href={`/facilities/${facilityId}/directions`}
          className="inline-flex items-center gap-1.5 font-semibold text-rose-700 hover:text-rose-800 hover:underline"
        >
          <Navigation className="h-4 w-4" />
          <span>Chỉ đường đến sân</span>
        </Link>
      </div>
      <h1 className="mt-5 text-3xl font-black">Chọn giờ chơi</h1>
      {ownFacility && <OwnFacilityNotice facilityId={facilityId} />}
      {!counter && !ownFacility && (
        <>
          <AdmissionNotice />
          <AdmissionErrorContext failure={policyFailure} />
        </>
      )}
      <p className="mt-1 text-base font-semibold text-slate-700">
        {court?.name || 'Sân'} {court?.code ? `(${court.code})` : ''} · {facility.data?.name}
      </p>
      <p className="mt-3 text-sm text-slate-500">
        Lịch trống cập nhật từ hệ thống. Chọn khung giờ chưa giữ chỗ; giờ theo{' '}
        {timezoneLabel(timezone || 'cơ sở')}.
      </p>
      {resource.data?.selectionStepMinutes === 30 && (
        <p className="mt-2 text-sm text-slate-500">
          Chọn liên tiếp từ 60 phút đến 4 giờ, theo bước 30 phút. Giá cuối tính theo toàn bộ khoảng
          chọn và làm tròn một lần.
        </p>
      )}
      {!counter && !ownFacility && (
        <aside className="mt-4 space-y-2 rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-950">
          <p className="font-semibold">Lượt đặt trực tiếp không được hoàn tiền khi khách tự hủy.</p>
          <p>
            Nếu không thể đến sân, bạn có thể đăng chuyển nhượng. Khi chuyển nhượng trực tiếp thành
            công, bạn nhận <strong>100% giá chuyển nhượng đã chốt</strong> từ người mua, không trừ
            phí. Nếu không chuyển nhượng được và không sử dụng sân, bạn mất toàn bộ tiền đã trả.
          </p>
        </aside>
      )}
      <div className="my-6 flex flex-wrap items-end justify-between gap-4">
        <label className="grid gap-2 text-sm">
          Ngày chơi
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
          onClick={() => {
            resource.reload()
            setSelection([])
            holdIntent.current = null
          }}
          disabled={busy}
        >
          Làm mới lịch
        </Button>
      </div>
      <section
        aria-label="Lịch trống"
        className="mb-6 rounded-2xl border border-emerald-200 bg-emerald-50 p-5"
      >
        <h2 className="font-bold">Các khoảng còn trống</h2>
        <p className="mt-1 text-sm text-slate-600">
          Tự cập nhật mỗi 5 giây. Chọn một khoảng hoặc tự chọn giờ bên dưới.
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          {ranges.map((range) => (
            <Button
              key={`range-${range.startsAt}`}
              variant="outline"
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
          <p className="mt-3 text-sm">Không còn giờ trống trong ngày này. Hãy chọn ngày khác.</p>
        )}
      </section>
      <section aria-label="Tự chọn giờ chơi" className="mb-6 rounded-2xl border p-5">
        <h2 className="font-bold">Tự chọn giờ chơi</h2>
        <div className="mt-3 grid gap-4 sm:grid-cols-2">
          <label className="grid gap-2 text-sm">
            Giờ bắt đầu
            <select
              className="rounded-lg border bg-white p-3"
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
          <label className="grid gap-2 text-sm">
            Giờ kết thúc
            <select
              className="rounded-lg border bg-white p-3"
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
        <p className="mt-3 text-sm text-slate-600">
          Chọn từ 60 phút đến 4 giờ. Chỉ các khoảng còn trống liên tục mới có thể đặt.
        </p>
      </section>
      {(resource.error || facility.error || courts.error || error) && (
        <p role="alert" className="mb-5 rounded-lg bg-red-50 p-4 text-sm text-red-700">
          {error || resource.error || facility.error || courts.error}
        </p>
      )}
      {resource.loading && <p role="status">Đang kiểm tra lịch trống…</p>}
      {resource.data?.slots.length === 0 && (
        <p className="rounded-xl border p-6 text-slate-500">
          Không có khung giờ mở cửa trong ngày này. Hãy chọn ngày khác.
        </p>
      )}
      <div className="grid gap-3 grid-cols-2 sm:grid-cols-3 lg:grid-cols-4">
        {resource.data?.slots.map((s) => {
          const selected = selection.some((v) => v.startsAt === s.startsAt)
          return (
            <button
              key={s.startsAt}
              type="button"
              disabled={busy || ownFacility || resource.loading || s.state !== 'AVAILABLE'}
              onClick={() => select(s)}
              aria-pressed={selected}
              className={`rounded-xl border p-4 text-left transition ${selected ? 'border-emerald-600 bg-emerald-600 text-white' : s.state === 'AVAILABLE' ? 'border-emerald-200 bg-emerald-50 hover:border-emerald-500' : 'border-slate-200 bg-slate-100 text-slate-400'}`}
            >
              <span className="block font-semibold">
                {time(s.startsAt)}–{time(s.endsAt)}
              </span>
              <span className="mt-2 block text-sm">
                {s.amount == null ? 'Chưa có giá' : money(s.amount, s.currency)}
              </span>
              <span className="mt-2 block text-xs">
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
          className="mt-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm"
        >
          <p>Khoảng chọn để lại giờ trống dưới 60 phút. Bạn vẫn có thể tiếp tục hoặc chọn gợi ý:</p>
          <div className="mt-2 flex flex-wrap gap-2">
            {pricing.quote.suggestions?.map((suggestion) => (
              <Button
                key={suggestion.startsAt + suggestion.endsAt}
                variant="outline"
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
        <aside className="sticky bottom-3 mt-8 flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-emerald-200 bg-white p-5 shadow-xl">
          <div>
            <p className="text-sm text-slate-500">
              {selection.length} khung giờ · {time(selection[0].startsAt)}–
              {time(selection[selection.length - 1].endsAt)}
            </p>
            <p className="mt-1 text-2xl font-bold" aria-live="polite">
              {pricing.loading
                ? 'Đang tính giá…'
                : pricing.quote
                  ? money(pricing.quote.amount)
                  : 'Chọn đủ thời lượng'}
            </p>
            {(pricing.error || unavailable) && (
              <p role="alert" className="mt-1 text-sm text-red-700">
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
            className="bg-emerald-600 px-6 text-white"
          >
            {busy ? 'Đang kiểm tra…' : user ? 'Xem tóm tắt và giữ chỗ' : 'Đăng nhập để đặt sân'}
          </Button>
        </aside>
      )}
    </div>
  )
}
