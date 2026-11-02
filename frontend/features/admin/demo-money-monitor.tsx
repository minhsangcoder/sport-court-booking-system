'use client'
import { demoVnd } from '@/features/facility/demo-report'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
type Monitoring = {
  mode: string
  settlements: {
    acquisitionId: string
    bookingId: string
    facilityName: string
    amount: string
    feeAmount: string
    sellerNetAmount: string
    payoutState: string
  }[]
  refunds: { id: string; bookingId: string; targetAmount: string; reason: string; state: string }[]
}
const states: Record<string, string> = {
  UNPAID: 'Chờ cơ sở trả',
  FAILED: 'Thất bại, còn phải thực hiện',
  PAID: 'Đã trả mô phỏng',
  NOT_REQUIRED: 'Không cần trả',
  PENDING: 'Chờ cơ sở hoàn',
  REFUNDED: 'Đã hoàn mô phỏng',
  NO_REFUND: 'Không có tiền hoàn',
}
export function AdminDemoMoneyMonitor({ query }: { query: string }) {
  const resource = useApi<Monitoring>(`/admin/bookings/demo-finance?${query}`)
  const data =
    resource.data &&
    Array.isArray(resource.data.settlements) &&
    Array.isArray(resource.data.refunds)
      ? resource.data
      : null
  return (
    <section className="mt-8 space-y-4 p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
      <h2 className="text-lg font-extrabold tracking-tight">Giao dịch mô phỏng của các cơ sở</h2>
      <p className="text-sm text-slate-600">
        Tiền sân, phí chuyển nhượng và nghĩa vụ trả/hoàn thuộc cơ sở. SportHub không hưởng doanh thu
        này. quản trị viên chỉ theo dõi; chủ cơ sở hoặc nhân viên có quyền thực hiện tiền mô phỏng.
      </p>
      {resource.loading && <p role="status">Đang tải giao dịch mô phỏng…</p>}
      {resource.error && (
        <p role="alert" className="text-red-700">
          {resource.error}
          <button className="ml-3 underline" onClick={resource.reload}>
            Thử lại
          </button>
        </p>
      )}
      {data && !resource.loading && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[650px] text-left text-sm">
              <caption className="sr-only">
                Khoản trả người bán và hoàn khách mô phỏng, chỉ đọc
              </caption>
              <thead>
                <tr>
                  {['Nghiệp vụ / cơ sở', 'Số tiền', 'Trạng thái', 'Chi tiết'].map((label) => (
                    <th key={label} className="p-3">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.settlements.map((item) => (
                  <tr className="border-t" key={item.acquisitionId}>
                    <td className="p-3">
                      Trả người bán · {item.facilityName || 'Cơ sở của lượt đặt'}
                      <span className="mt-1 block text-xs text-slate-500">
                        Phí cơ sở: {demoVnd(item.feeAmount)}
                      </span>
                    </td>
                    <td className="p-3">{demoVnd(item.sellerNetAmount)}</td>
                    <td className="p-3">{states[item.payoutState] || 'Đang đồng bộ'}</td>
                    <td className="p-3">
                      <Link
                        className="font-semibold text-emerald-700 hover:underline"
                        href={`/admin/bookings/${item.bookingId}`}
                      >
                        Xem lượt đặt
                      </Link>
                    </td>
                  </tr>
                ))}
                {data.refunds.map((item) => (
                  <tr className="border-t" key={item.id}>
                    <td className="p-3">
                      {item.reason === 'TECHNICAL_TRANSFER_FAILURE'
                        ? 'Hoàn do bàn giao thất bại'
                        : 'Hoàn theo chính sách cơ sở'}
                    </td>
                    <td className="p-3">{demoVnd(item.targetAmount)}</td>
                    <td className="p-3">{states[item.state] || 'Đang đồng bộ'}</td>
                    <td className="p-3">
                      <Link
                        className="font-semibold text-emerald-700 hover:underline"
                        href={`/admin/bookings/${item.bookingId}`}
                      >
                        Xem lượt đặt
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.settlements.length + data.refunds.length === 0 && (
            <p className="text-sm text-slate-500">Không có nghĩa vụ mô phỏng phát sinh trong kỳ.</p>
          )}
          <p className="text-xs text-slate-500">
            Tối đa 200 khoản mỗi loại trong kỳ; khoản trả theo ngày bàn giao, yêu cầu hoàn theo ngày
            phát sinh. Trạng thái thực hiện tại lúc tải. Không phát sinh tiền thật.
          </p>
        </>
      )}
    </section>
  )
}
