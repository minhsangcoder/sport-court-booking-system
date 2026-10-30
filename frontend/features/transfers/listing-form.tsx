'use client'
import Link from 'next/link'
import { useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import type { BookingDetail } from '@/features/booking/types'
import { TransferFinanceNotice } from './finance-notice'
import type { Listing, TransferFinanceTerms } from './types'
import { BankDestinationForm } from '@/features/payments/bank-destination-form'
import { useAuth } from '@/lib/auth-context'
import { AdmissionNotice } from '@/features/booking/admission-notice'
import { PageBanner } from '@/components/page-banner'
import { CourtLines } from '@/features/home/home-art'
import {
  amberNotice,
  bannerLink,
  card,
  fieldLabel,
  outlinePill,
  primaryPill,
} from './transfer-styles'
export function ListingForm({ bookingId, listingId }: { bookingId?: string; listingId?: string }) {
  const { user } = useAuth()
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded
        key={`${user?.id}:${bookingId}:${listingId}`}
        bookingId={bookingId}
        listingId={listingId}
      />
    </RequireAuth>
  )
}
function localInput(value: string) {
  const date = new Date(value)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
function Loaded({ bookingId, listingId }: { bookingId?: string; listingId?: string }) {
  const router = useRouter()
  const booking = useApi<BookingDetail>(bookingId ? `/bookings/${bookingId}` : null)
  const listing = useApi<Listing>(listingId ? `/transfers/${listingId}` : null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [terms, setTerms] = useState<TransferFinanceTerms | null>(null)
  const [draft, setDraft] = useState<{
    bookingId?: string
    price: number
    deadline: string
    bankDestinationRevisionId?: string
    expectedVersion?: number
  } | null>(null)
  const [bankDestination, setBankDestination] = useState('')
  const real = listingId
    ? listing.data?.financeTerms?.protocol === 'PEER_RESALE_V1'
    : booking.data?.booking.priceSnapshot.moneyClass === 'REAL'
  const [consent, setConsent] = useState(false)
  const intent = useRef<{ payload: string; key: string } | null>(null)
  async function quote(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const form = new FormData(event.currentTarget)
    if (!real || !bankDestination) {
      setError('Chọn tài khoản ngân hàng của bạn trước khi xem điều khoản và đăng tin.')
      return
    }
    const body = {
      bookingId,
      price: Number(form.get('price')),
      deadline: new Date(String(form.get('deadline'))).toISOString(),
      bankDestinationRevisionId: bankDestination,
      ...(listingId ? { expectedVersion: listing.data?.version } : {}),
    }
    setBusy(true)
    setError('')
    setTerms(null)
    setConsent(false)
    try {
      const result = await api<TransferFinanceTerms>(
        listingId ? `/transfers/${listingId}/quote` : '/transfers/quote',
        { method: 'POST', body: JSON.stringify(body) },
      )
      if (result.protocol !== 'PEER_RESALE_V1')
        throw new Error('Điều khoản chuyển nhượng đã cũ. Hãy tải lại trang.')
      setTerms(result)
      setDraft(body)
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : 'Không thể lấy điều khoản chuyển nhượng trực tiếp.',
      )
    } finally {
      setBusy(false)
    }
  }
  async function publish() {
    if (!draft || !terms || !consent || busy) return
    setBusy(true)
    setError('')
    const payload = JSON.stringify({ ...draft, acceptedTermsHash: terms.termsHash })
    if (intent.current?.payload !== payload) intent.current = { payload, key: crypto.randomUUID() }
    try {
      const result = await api<Listing>(listingId ? `/transfers/${listingId}` : '/transfers', {
        method: listingId ? 'PUT' : 'POST',
        headers: { 'Idempotency-Key': intent.current.key },
        body: payload,
      })
      router.push(`/transfers/${result.id}`)
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : 'Không thể lưu tin. Hãy kiểm tra lại giá và điều khoản chuyển nhượng.',
      )
      setTerms(null)
      setConsent(false)
    } finally {
      setBusy(false)
    }
  }
  if ((booking.loading && !booking.data) || (listing.loading && !listing.data))
    return (
      <p role="status" className="p-8">
        Đang tải lượt đặt…
      </p>
    )
  if (!booking.data && !listing.data)
    return (
      <p role="alert" className="p-8 text-red-700">
        {booking.error || listing.error}
        <button
          className="ml-4 underline"
          onClick={() => {
            booking.reload()
            listing.reload()
          }}
        >
          Thử lại
        </button>
      </p>
    )
  const amount = booking.data?.booking.amount ?? listing.data!.originalAmount
  const starts = booking.data?.booking.startsAt ?? listing.data!.startsAt
  const currency = booking.data?.booking.currency ?? listing.data!.currency
  if (!real)
    return (
      <div>
        <PageBanner
          icon="pickleball"
          eyebrow={
            <Link
              href={listingId ? `/transfers/${listingId}` : `/customer/bookings/${bookingId}`}
              className={bannerLink}
            >
              Quay lại chi tiết
            </Link>
          }
          title="Không thể tạo chuyển nhượng tiền thật từ giao dịch cũ"
        />
        <div className="home-rise mx-auto max-w-3xl px-4 py-7 sm:px-6 sm:py-9">
          <section
            className={`${card} space-y-3 p-5 text-sm leading-relaxed text-slate-600 sm:p-7 dark:text-slate-300`}
          >
            <p>
              Lượt đặt mô phỏng và giao dịch chuyển nhượng cũ được giữ trong lịch sử. Tin đăng mới
              chỉ áp dụng cho lượt đặt đã thanh toán thật.
            </p>
            <p>
              Với lượt đặt hợp lệ, người mua trả 100% giá đã chốt trực tiếp vào tài khoản người bán
              bằng QR SePay, không có phí chuyển nhượng.
            </p>
          </section>
        </div>
      </div>
    )
  return (
    <div>
      <PageBanner
        icon="pickleball"
        eyebrow={
          <Link
            href={listingId ? `/transfers/${listingId}` : `/customer/bookings/${bookingId}`}
            className={bannerLink}
          >
            Quay lại chi tiết
          </Link>
        }
        title={listingId ? 'Sửa tin chuyển nhượng' : 'Đăng lượt đặt lên chợ'}
        description={`Giá gốc: ${money(amount, currency)}. Sân và lịch chơi được giữ nguyên.`}
      />
      <div className="home-rise mx-auto max-w-3xl space-y-6 px-4 py-7 sm:px-6 sm:py-9">
        <AdmissionNotice market />
        {real && (
          <BankDestinationForm
            value={bankDestination}
            onChange={(revision) => {
              setBankDestination(revision)
              setTerms(null)
              setDraft(null)
              setConsent(false)
            }}
            disabled={busy}
          />
        )}
        <form
          onSubmit={quote}
          onChange={() => {
            setTerms(null)
            setConsent(false)
          }}
          className={`${card} space-y-5 p-5 sm:p-7 [animation-delay:60ms]`}
        >
          <CourtLines className="pointer-events-none absolute -right-20 -top-12 -z-10 w-80 -rotate-6 text-emerald-600/[0.05]" />
          <div className="grid gap-5 sm:grid-cols-2">
            <label className={fieldLabel}>
              Giá chuyển nhượng ({currency})
              <Input
                name="price"
                type="number"
                min={1}
                max={amount}
                step={1}
                required
                defaultValue={listing.data?.price ?? amount}
                disabled={busy}
                className="h-11 rounded-xl"
              />
            </label>
            <label className={fieldLabel}>
              Hạn nhận lượt đặt
              <Input
                name="deadline"
                type="datetime-local"
                required
                max={localInput(starts)}
                defaultValue={localInput(listing.data?.deadline ?? starts)}
                disabled={busy}
                className="h-11 rounded-xl"
              />
            </label>
          </div>
          <p className={amberNotice}>
            Người mua chuyển khoản 100% giá đã chốt trực tiếp cho bạn qua QR SePay. Bạn xác nhận đã
            nhận đủ tiền để bàn giao sân; không có phí chuyển nhượng.
          </p>
          <Button type="submit" variant="outline" disabled={busy} className={outlinePill}>
            {busy ? 'Đang xử lý…' : 'Xem điều khoản'}
          </Button>
        </form>
        {terms && (
          <section className={`${card} space-y-5 p-5 sm:p-7`}>
            <TransferFinanceNotice terms={terms} />
            <label className="flex items-start gap-3 text-sm text-slate-700 dark:text-slate-300">
              <input
                type="checkbox"
                checked={consent}
                onChange={(event) => setConsent(event.target.checked)}
                disabled={busy}
                className="mt-1 size-4 accent-emerald-600"
              />
              Tôi đồng ý điều khoản, chia sẻ tài khoản đã chọn cho người mua và gửi ngân hàng, số
              tài khoản, số tiền, nội dung tới SePay để tạo QR. Tôi tự kiểm tra tiền thực nhận trước
              khi bàn giao.
            </label>
            <Button
              type="button"
              className={`${primaryPill} w-full`}
              onClick={publish}
              disabled={!consent || busy}
            >
              {busy ? 'Đang lưu…' : listingId ? 'Lưu thay đổi' : 'Đăng tin chuyển nhượng'}
            </Button>
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
    </div>
  )
}
