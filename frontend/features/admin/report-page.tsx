'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import { money } from '@/features/schedule/types'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
import { AdminPage } from './admin-nav'
import { CollectionsReport } from './collections-report'
import { AdminDemoMoneyMonitor } from './demo-money-monitor'
import { AdminRealMoneyMonitor } from './real-money-monitor'
type BookingStats = {
  from: string
  to: string
  states: { status: string; count: number; booking_value: number }[]
}
type PaymentStats = {
  from: string
  to: string
  orders: { purpose: string; status: string; currency: string; count: number; amount: number }[]
  escrow: { state: string; currency: string; count: number; amount: number }[]
  refundReviews: { state: string; count: number }[]
}
export function ReportPage() {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Loaded />
    </RequireAuth>
  )
}
export function reportRange(f: FormData) {
  const p = new URLSearchParams()
  const from = f.get('from'),
    to = f.get('to')
  if (from) p.set('from', new Date(`${from}T00:00:00+07:00`).toISOString())
  if (to) {
    const end = new Date(`${to}T00:00:00+07:00`)
    end.setDate(end.getDate() + 1)
    p.set('to', end.toISOString())
  }
  return p.toString()
}
function Loaded() {
  const [query, setQuery] = useState('')
  const users = useApi<{ status: string; total: number }[]>('/admin/accounts/statistics')
  const bookings = useApi<BookingStats>(`/admin/bookings/statistics?${query}`)
  const payments = useApi<PaymentStats>(`/admin/payments/statistics?${query}`)
  const error = users.error || bookings.error || payments.error
  return (
    <AdminPage active="reports" title="Giám sát hoạt động hệ thống">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          setQuery(reportRange(new FormData(e.currentTarget)))
        }}
        className="mt-6 flex flex-wrap items-end gap-4 p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
      >
        <label className="grid gap-2 text-sm">
          Từ ngày
          <Input name="from" type="date" />
        </label>
        <label className="grid gap-2 text-sm">
          Đến hết ngày
          <Input name="to" type="date" />
        </label>
        <Button type="submit" className="bg-emerald-600 text-white">
          Áp dụng khoảng thời gian
        </Button>
        <p className="text-xs text-slate-500">
          Mặc định 30 ngày gần nhất; số liệu lượt đặt/giao dịch theo lúc tạo.
        </p>
      </form>
      {(users.loading || bookings.loading || payments.loading) && (
        <p role="status" className="mt-6">
          Đang tổng hợp số liệu…
        </p>
      )}
      {error && (
        <p role="alert" className="mt-6 text-red-700">
          {error}
          <button
            className="ml-4 underline"
            onClick={() => {
              users.reload()
              bookings.reload()
              payments.reload()
            }}
          >
            Thử lại
          </button>
        </p>
      )}
      <section className="mt-8">
        <h2 className="text-lg font-extrabold tracking-tight">Tài khoản hiện tại</h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-3">
          {users.data?.map((x) => (
            <div
              className="p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
              key={x.status}
            >
              <p className="text-sm text-slate-500">{displayLabel(x.status)}</p>
              <p className="mt-3 text-3xl font-bold text-emerald-700">{x.total}</p>
            </div>
          ))}
        </div>
      </section>
      <section className="mt-8">
        <h2 className="text-lg font-extrabold tracking-tight">Lượt đặt theo trạng thái</h2>
        <div className="mt-4 grid gap-4 sm:grid-cols-3">
          {bookings.data?.states.map((x) => (
            <div
              className="p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
              key={x.status}
            >
              <p className="text-sm text-slate-500">{displayLabel(x.status)}</p>
              <p className="mt-3 text-3xl font-bold">{x.count}</p>
              <p className="mt-3 text-xs text-slate-500">
                Giá trị lượt đặt gốc: {money(x.booking_value)}
              </p>
            </div>
          ))}
        </div>
        {bookings.data?.states.length === 0 && (
          <p className="mt-4 text-slate-500">Chưa có lượt đặt trong kỳ.</p>
        )}
      </section>
      <section className="mt-8">
        <h2 className="text-lg font-extrabold tracking-tight">Giao dịch theo nghiệp vụ</h2>
        <div className="mt-4 divide-y rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          {payments.data?.orders.map((x) => (
            <div
              className="flex flex-wrap justify-between gap-3 p-5"
              key={`${x.purpose}-${x.status}-${x.currency}`}
            >
              <div>
                <p className="font-semibold">
                  {displayLabel(x.purpose)} · {displayLabel(x.status)}
                </p>
                <p className="mt-2 text-xs text-slate-500">{x.count} giao dịch</p>
              </div>
              <strong className="text-emerald-700">{money(x.amount, x.currency)}</strong>
            </div>
          ))}
        </div>
        <p className="mt-3 text-xs text-slate-500">
          Giá trị giao dịch ứng dụng theo từng trạng thái, gồm dữ liệu mô phỏng. Không phải doanh
          thu SportHub hoặc xác nhận tiền thật.
        </p>
      </section>
      <section className="mt-8 rounded-2xl bg-amber-50 p-6">
        <h2 className="text-lg font-extrabold tracking-tight text-amber-900">
          Khoản giữ và yêu cầu xem xét trước đây
        </h2>
        {payments.data?.escrow.map((x) => (
          <p key={`${x.state}-${x.currency}`} className="mt-4 text-sm">
            {displayLabel(x.state)}: {x.count} khoản · {money(x.amount, x.currency)}
          </p>
        ))}
        {payments.data?.refundReviews.map((x) => (
          <p key={x.state} className="mt-3 text-sm">
            Hoàn tiền {displayLabel(x.state)}: {x.count} yêu cầu
          </p>
        ))}
        <p className="mt-4 text-sm text-amber-900">
          Các yêu cầu cần xem xét trước đây không tự được coi là đã hoàn. Khoản trả và hoàn mô phỏng
          mới của cơ sở được theo dõi riêng bên dưới.
        </p>
      </section>
      <AdminDemoMoneyMonitor query={query} />
      <AdminRealMoneyMonitor query={query} />
      <CollectionsReport />
    </AdminPage>
  )
}
