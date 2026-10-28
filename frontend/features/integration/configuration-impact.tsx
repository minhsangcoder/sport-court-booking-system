import { displayLabel } from '@/lib/display-labels'
import { ApiError } from '@/lib/api'
type Reservation = { id: string; courtId: string; startsAt: string; endsAt: string; state: string }
export type ConfigurationImpact = {
  affectedCount: number
  affectedReservations: Reservation[]
  truncated: boolean
}
export function readImpact(error: unknown): ConfigurationImpact | null {
  if (
    !(error instanceof ApiError) ||
    error.details?.reason !== 'CONFIGURATION_IMPACT' ||
    !Array.isArray(error.details.affectedReservations)
  )
    return null
  return error.details as unknown as ConfigurationImpact
}
export function ImpactNotice({ impact }: { impact: ConfigurationImpact | null }) {
  if (!impact) return null
  return (
    <section
      className="space-y-3 rounded-xl border border-amber-200 bg-amber-50 p-5"
      aria-label="Lịch đặt bị ảnh hưởng"
    >
      <h3 className="font-bold">Có {impact.affectedCount} lượt giữ chỗ / lượt đặt bị ảnh hưởng</h3>
      <p className="text-sm">
        Cấu hình chưa được lưu. Xử lý các lượt đặt trước rồi thử lại; lịch sử và thanh toán được giữ
        nguyên.
      </p>
      <ul className="space-y-2 text-sm">
        {impact.affectedReservations.map((r) => (
          <li key={r.id}>
            {displayLabel(r.state)} · {new Date(r.startsAt).toLocaleString('vi-VN')} →{' '}
            {new Date(r.endsAt).toLocaleString('vi-VN')} · {r.id.slice(0, 8)}
          </li>
        ))}
      </ul>
      {impact.truncated && (
        <p className="text-sm">Hiển thị tối đa 50 lượt; xem lịch vận hành để kiểm tra đầy đủ.</p>
      )}
    </section>
  )
}
