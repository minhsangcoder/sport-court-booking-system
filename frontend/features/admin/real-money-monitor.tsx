'use client'
import { useApi } from '@/lib/use-api'
import { demoVnd } from '@/features/facility/demo-report'
import {
  obligationKinds,
  obligationLabels,
  type Obligation,
} from '@/features/payments/finance-types'

export function AdminRealMoneyMonitor({ query }: { query: string }) {
  const finance = useApi<{
    mode: string
    obligations: Obligation[]
    refunds: { id: string; reason: string; targetAmount: string }[]
  }>(`/admin/bookings/facility-finance?${query}`)
  const collections = useApi<{
    bankCredits: { id: string; payment_id: string; amount: string; bank_time: string }[]
    sessions: { payment_id: string; state: string; last_error_code: string | null }[]
  }>(`/admin/payments/real-collections?${query}`)
  return (
    <section className="mt-8 space-y-4 p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
      <h2 className="text-lg font-extrabold tracking-tight">
        Giám sát giao dịch ngân hàng và nghĩa vụ của cơ sở
      </h2>
      <p className="text-sm text-slate-600">
        Chỉ đọc, tối đa 200 dòng mỗi nguồn. Tiền vào có chứng từ ngân hàng; tiền ra do cơ sở xác
        nhận thủ công. Admin không được xác nhận tiền hoặc thay cơ sở chi trả. Đây không phải doanh
        thu SportHub.
      </p>
      {(finance.loading || collections.loading) && <p role="status">Đang tải chứng từ…</p>}
      {(finance.error || collections.error) && (
        <p role="alert" className="text-red-700">
          {finance.error || collections.error}
        </p>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <section className="space-y-2">
          <h3 className="font-semibold">Tiền ngân hàng nhận theo thời điểm giao dịch</h3>
          {collections.data?.bankCredits.map((item) => (
            <p key={item.id} className="rounded-2xl border border-slate-200/90 p-3 text-sm">
              {demoVnd(String(item.amount))} · {new Date(item.bank_time).toLocaleString('vi-VN')} ·
              Đã xác thực ngân hàng
            </p>
          ))}
        </section>
        <section className="space-y-2">
          <h3 className="font-semibold">Khoản cơ sở phải thực hiện</h3>
          {finance.data?.obligations.map((item) => (
            <p key={item.id} className="rounded-2xl border border-slate-200/90 p-3 text-sm">
              {obligationKinds[item.kind] ?? 'Khoản phải trả'} · {demoVnd(item.amount)} ·{' '}
              {obligationLabels[item.state] ?? 'Đang đối soát'}
            </p>
          ))}
        </section>
      </div>
      {collections.data?.sessions.some((item) => item.state === 'REVIEW') && (
        <p className="rounded-2xl bg-amber-50 p-3 text-sm text-amber-900">
          Có QR hoặc khoản thu cần đối soát. Kiểm tra giao dịch gốc trước khi xử lý lại.
        </p>
      )}
    </section>
  )
}
