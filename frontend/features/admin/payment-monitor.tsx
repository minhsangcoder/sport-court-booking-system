'use client'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { BannerPage } from '@/components/page-banner'
import { RecordDetails } from '@/components/record-details'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Payment } from '@/features/booking/types'
import { money } from '@/features/schedule/types'
import { displayLabel } from '@/lib/display-labels'
import { translateError } from '@/lib/error-translator'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
import { AdminPage } from './admin-nav'
import { FacilityPicker, facilityCode, type PickedFacility } from './facility-picker'
const FACILITY_KEY = 'sporthub.admin.payments.facility'
export function PaymentMonitor() {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Loaded />
    </RequireAuth>
  )
}
function Loaded() {
  // Keep the chosen facility when the Admin comes back from a transaction detail.
  // RequireAuth renders this only in the browser, after the session is ready.
  const [facility, setFacility] = useState<PickedFacility | null>(() => {
    try {
      const saved = sessionStorage.getItem(FACILITY_KEY)
      return saved ? (JSON.parse(saved) as PickedFacility) : null
    } catch {
      return null
    }
  })
  function choose(next: PickedFacility | null) {
    setFacility(next)
    if (next) sessionStorage.setItem(FACILITY_KEY, JSON.stringify(next))
    else sessionStorage.removeItem(FACILITY_KEY)
    setFilters('')
  }
  const [filters, setFilters] = useState('')
  const r = useApi<Payment[]>(
    facility
      ? `/admin/payments?${new URLSearchParams([['facilityId', facility.id], ...new URLSearchParams(filters)])}`
      : null,
  )
  function search(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = new FormData(e.currentTarget),
      p = new URLSearchParams()
    for (const key of ['q', 'status', 'purpose']) {
      const v = String(f.get(key) || '')
      if (v) p.set(key, v)
    }
    setFilters(p.toString())
  }
  function exportRows() {
    if (!r.data) return
    const quote = (value: unknown) => `"${String(value ?? '').replaceAll('"', '""')}"`
    const rows = [
      [
        'Mã thanh toán',
        'Mã lượt đặt',
        'Mã tham chiếu',
        'Nghiệp vụ',
        'Số tiền',
        'Loại tiền',
        'Trạng thái',
      ],
      ...r.data.map((p) => [
        p.id,
        p.bookingId,
        p.providerReference,
        displayLabel(p.purpose),
        p.amount,
        p.currency,
        displayLabel(p.status),
      ]),
    ]
    const url = URL.createObjectURL(
      new Blob(['\uFEFF' + rows.map((row) => row.map(quote).join(',')).join('\r\n')], {
        type: 'text/csv;charset=utf-8',
      }),
    )
    const a = document.createElement('a')
    a.href = url
    a.download = `sporthub-thanh-toan-${facility?.id ?? 'co-so'}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }
  return (
    <AdminPage
      active="payments"
      title="Giám sát giao dịch"
      actions={
        <Button variant="outline" onClick={exportRows} disabled={!r.data?.length}>
          Xuất kết quả hiển thị
        </Button>
      }
    >
      {!facility ? (
        <section className="mt-6 p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          <h2 className="text-lg font-extrabold tracking-tight">Chọn cơ sở cần giám sát</h2>
          <p className="mt-1 mb-4 text-sm text-slate-500">
            Giao dịch được xem theo từng cơ sở. Chọn trong danh sách hoặc tìm theo tên, mã cơ sở.
          </p>
          <FacilityPicker onPick={choose} />
        </section>
      ) : (
        <>
          <section className="mt-6 flex flex-wrap items-center justify-between gap-4 p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
            <div>
              <p className="text-sm text-slate-500">Cơ sở đang xem</p>
              <p className="mt-1 text-lg font-bold">{facility.name}</p>
              <p className="mt-1 break-all text-xs text-slate-500">
                Mã {facilityCode(facility.id)} · {displayLabel(facility.status)}
              </p>
            </div>
            <Button variant="outline" onClick={() => choose(null)}>
              Đổi cơ sở
            </Button>
          </section>
          <form
            key={facility.id}
            onSubmit={search}
            className="mt-6 grid gap-4 p-5 md:grid-cols-4 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
          >
            <label className="grid gap-2 text-sm">
              Mã hoặc tham chiếu
              <Input name="q" placeholder="Thanh toán, lượt đặt, mã cổng" />
            </label>
            <label className="grid gap-2 text-sm">
              Trạng thái
              <select name="status" className="h-11 rounded-xl border bg-white px-3.5">
                <option value="">Tất cả</option>
                {['PENDING', 'SUCCESS', 'FAILED', 'EXPIRED'].map((s) => (
                  <option key={s} value={s}>
                    {displayLabel(s)}
                  </option>
                ))}
              </select>
            </label>
            <label className="grid gap-2 text-sm">
              Nghiệp vụ
              <select name="purpose" className="h-11 rounded-xl border bg-white px-3.5">
                <option value="">Tất cả</option>
                <option value="BOOKING">Đặt sân</option>
                <option value="GROUP_CONTRIBUTION">Góp tiền nhóm</option>
                <option value="TRANSFER">Chuyển nhượng</option>
              </select>
            </label>
            <Button type="submit" className="mt-auto bg-emerald-600 text-white">
              Tra cứu
            </Button>
          </form>
          {r.loading && (
            <p role="status" className="mt-6">
              Đang tải giao dịch…
            </p>
          )}
          {r.error && (
            <p role="alert" className="mt-6 text-red-700">
              {r.error}
              <button className="ml-4 underline" onClick={r.reload}>
                Thử lại
              </button>
            </p>
          )}
          {r.data?.length === 0 && (
            <p className="mt-6 p-6 text-slate-500 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
              Cơ sở này chưa có giao dịch phù hợp.
            </p>
          )}
          <div className="mt-6 divide-y rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
            {r.data?.map((p) => (
              <Link
                key={p.id}
                href={`/admin/payments/${p.id}`}
                className="flex flex-wrap justify-between gap-4 p-5 hover:bg-emerald-50"
              >
                <div>
                  <p className="font-bold">{p.providerReference}</p>
                  <p className="mt-2 text-sm text-slate-500">
                    {displayLabel(p.purpose)} · {displayLabel(p.status)}
                  </p>
                </div>
                <strong className="text-emerald-700">{money(p.amount, p.currency)}</strong>
              </Link>
            ))}
          </div>
          <p className="mt-4 text-xs text-slate-500">
            Tối đa 200 kết quả. Xuất CSV áp dụng cho danh sách đang hiển thị.
          </p>
        </>
      )}
    </AdminPage>
  )
}
type PaymentDetail = {
  payment: Payment
  audit: unknown[]
  callbacks: unknown[]
  requests: unknown[]
  refunds: { state: string; reason: string; requester_id: string }[]
  escrow: { state: string; amount: number; currency: string; policy_reference: string }[]
}
export function AdminPaymentDetail({ id }: { id: string }) {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Detail id={id} />
    </RequireAuth>
  )
}
function Detail({ id }: { id: string }) {
  const r = useApi<PaymentDetail>(`/admin/payments/${id}`)
  const [tab, setTab] = useState('audit')
  if (r.loading)
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
  const p = r.data.payment
  const records =
    tab === 'audit' ? r.data.audit : tab === 'callbacks' ? r.data.callbacks : r.data.requests
  return (
    <BannerPage
      eyebrow={
        <Link className={bannerLink} href="/admin/payments">
          Giám sát giao dịch
        </Link>
      }
      title={p.providerReference ?? ''}
      width="max-w-4xl"
    >
      <section className="mt-6 space-y-3 p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
        <p className="text-3xl font-bold text-emerald-700">{money(p.amount, p.currency)}</p>
        <p>
          {displayLabel(p.purpose)} · {displayLabel(p.status)}
        </p>
        <p className="break-all text-sm text-slate-500">Người trả {p.payerId}</p>
        <Link
          href={`/admin/bookings/${p.bookingId}`}
          className="block text-sm font-semibold text-emerald-700 hover:underline"
        >
          Xem lượt đặt liên quan
        </Link>
      </section>
      {r.data.escrow.map((x, i) => (
        <section key={i} className="mt-5 rounded-xl bg-amber-50 p-5 text-sm text-amber-900">
          <h2 className="font-bold">Tiền tạm giữ: {displayLabel(x.state)}</h2>
          <p className="mt-2">
            {money(x.amount, x.currency)} ·{' '}
            {displayLabel(
              x.policy_reference,
              translateError(x.policy_reference, { preserveUnknown: true }),
            )}
          </p>
        </section>
      ))}
      {r.data.refunds.map((x, i) => (
        <section
          key={i}
          className="mt-5 p-5 text-sm rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
        >
          <p className="font-bold">Yêu cầu hoàn tiền: {displayLabel(x.state)}</p>
          <p className="mt-2">{translateError(x.reason, { preserveUnknown: true })}</p>
        </section>
      ))}
      <div
        role="tablist"
        aria-label="Bằng chứng giao dịch"
        className="mt-8 flex gap-3 border-b pb-4"
      >
        {[
          ['audit', 'Lịch sử xử lý'],
          ['callbacks', 'Thông báo kết quả đã xác minh'],
          ['requests', 'Khóa chống lặp'],
        ].map(([value, label]) => (
          <button
            role="tab"
            aria-selected={tab === value}
            key={value}
            onClick={() => setTab(value)}
            className={`rounded-full px-4 py-2 text-sm ${tab === value ? 'bg-emerald-600 text-white' : 'bg-slate-100'}`}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="mt-5 space-y-4">
        {records.map((x, i) => (
          <div key={i} className="overflow-x-auto rounded-xl bg-slate-50 p-5 text-xs">
            <RecordDetails value={x} />
          </div>
        ))}
      </div>
    </BannerPage>
  )
}
