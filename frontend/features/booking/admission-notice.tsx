'use client'
import Link from 'next/link'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import type { ApiError } from '@/lib/api'

export type AdmissionStatus = {
  policyVersion: number
  maxConcurrent: number
  policyAvailability: 'COMPLETE' | 'UNAVAILABLE' | 'NOT_ENABLED'
  holdBlockedUntil: string | null
  onlineBlockedUntil: string | null
  marketBlockedUntil: string | null
  marketPermanent: boolean
  strikeCount: number
  resources: { kind: string; resourceId: string; expiresAt: string | null }[]
}
export function admissionMessages(status: AdmissionStatus, market: boolean, now = Date.now()) {
  const messages: string[] = []
  const until = (value: string | null) =>
    value && Date.parse(value) > now
      ? new Date(value).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' })
      : null
  if (status.policyAvailability === 'UNAVAILABLE')
    messages.push(
      'Chưa đối chiếu được lịch sử giữ chỗ. Lượt giữ mới có thể tạm bị chặn; lịch đã trả tiền và hồ sơ đang mở vẫn được phục vụ.',
    )
  if (status.policyAvailability === 'COMPLETE') {
    const online = until(status.onlineBlockedUntil),
      hold = until(status.holdBlockedUntil)
    if (online)
      messages.push(
        `Đã đạt năm lượt bỏ hoặc hết hạn trong ngày. Tạm nghỉ đặt sân trực tuyến đến ${online}.`,
      )
    else if (hold)
      messages.push(
        `Đã bỏ hoặc hết hạn ba lượt liên tiếp trong một giờ. Tạm nghỉ giữ chỗ đến ${hold}.`,
      )
  }
  if (market) {
    const end = until(status.marketBlockedUntil)
    if (status.marketPermanent)
      messages.push(
        'Bạn bị cấm tham gia chợ chuyển nhượng vĩnh viễn sau ba kết luận gian dối. Lịch đã thanh toán và hồ sơ cũ vẫn được sử dụng, bổ sung chứng cứ và đối chiếu tiền.',
      )
    else if (end)
      messages.push(
        `Chợ chuyển nhượng tạm khóa 30 ngày theo quyết định gian dối, đến ${end}. Lịch đã thanh toán và hồ sơ cũ vẫn được phục vụ.`,
      )
  }
  return messages
}
export function AdmissionNotice({ market = false }: { market?: boolean }) {
  const { user } = useAuth()
  return user?.roles.includes('CUSTOMER') ? (
    <Loaded key={`${user.id}:${market}`} market={market} />
  ) : null
}
export function AdmissionErrorContext({ failure }: { failure: ApiError | null }) {
  const retry = failure?.details?.retryAt
  if (typeof retry !== 'string' || !Number.isFinite(Date.parse(retry))) return null
  return (
    <p className="text-sm text-amber-900">
      Có thể thử lại sau {new Date(retry).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' })}
      .{' '}
      <Link className="underline" href="/customer/bookings">
        Mở lịch đã đặt
      </Link>
    </p>
  )
}
function Loaded({ market }: { market: boolean }) {
  const read = useApi<AdmissionStatus>('/bookings/admission')
  if (read.loading)
    return (
      <p role="status" className="text-sm text-slate-600">
        Đang kiểm tra lượt giữ hiện có…
      </p>
    )
  if (read.error || !read.data)
    return (
      <p role="alert" className="text-sm text-amber-800">
        Chưa tải được trạng thái giữ chỗ. Hệ thống sẽ kiểm tra lại khi bạn gửi yêu cầu.{' '}
        <button className="underline" onClick={read.reload}>
          Tải lại
        </button>
      </p>
    )
  const messages = admissionMessages(read.data, market)
  if (!messages.length && !read.data.resources.length) return null
  return (
    <aside
      className="space-y-2 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950"
      aria-label="Điều kiện giữ chỗ"
    >
      {messages.map((message) => (
        <p key={message}>{message}</p>
      ))}
      {read.data.resources.length > 0 && (
        <>
          <p>
            Bạn đang có lượt giữ sân hoặc mua chuyển nhượng chưa xong. Hoàn tất hoặc hủy trước khi
            giữ thêm.
          </p>
          <ul className="list-inside list-disc">
            {read.data.resources.map((resource) => (
              <li key={`${resource.kind}:${resource.resourceId}`}>
                <Link
                  className="underline"
                  href={
                    resource.kind.includes('ACQUISITION')
                      ? `/customer/transfers/acquisitions/${resource.resourceId}`
                      : resource.kind === 'ONLINE_BOOKING'
                        ? `/customer/booking/holds/${resource.resourceId}`
                        : '/customer/bookings'
                  }
                >
                  Mở{' '}
                  {resource.kind.includes('ACQUISITION')
                    ? 'giao dịch chuyển nhượng'
                    : 'lượt giữ sân'}
                </Link>
                {resource.expiresAt &&
                  ` · hạn ${new Date(resource.expiresAt).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' })}`}
              </li>
            ))}
          </ul>
        </>
      )}
      <p>
        Bỏ lượt giữ sẽ không cho đặt lại khoảng thời gian giao nhau trên cùng sân trong 15 phút.
        Thời hạn do server quyết định.
      </p>
    </aside>
  )
}
