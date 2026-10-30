'use client'
import Link from 'next/link'
import type { BankDestination } from './types'

export type BankProfile = {
  version: number
  initialized: boolean
  canRegisterFirst: boolean
  canChange: boolean
  activeRevisionId: string | null
  destination: BankDestination | null
  responsibilities: { kind: string; resourceId: string; effectiveUntil: string | null }[]
}
const reasons: Record<string, string> = {
  BOOKING: 'Lịch sân chưa kết thúc',
  GROUP_BOOKING: 'Lịch sân nhóm chưa kết thúc',
  HOLD: 'Đang giữ sân',
  TRANSFER_LISTING: 'Tin chuyển nhượng đang mở',
  LEGACY_ACQUISITION: 'Giao dịch chuyển nhượng đang xử lý',
  PEER_ACQUISITION: 'Phiên mua chưa kết thúc',
  PEER_DISPUTE: 'Quyền sân đang tranh chấp',
  PEER_ACKNOWLEDGED_RETURN: 'Khoản trả lại chờ người mua xác nhận',
  FACILITY_DISBURSEMENT: 'Khoản trả hoặc hoàn chưa hoàn tất',
  REAL_UNAPPLIED_FUNDS: 'Tiền đang chờ đối chiếu',
  LEGACY_GROUP_REFUND_REVIEW: 'Khoản góp nhóm cũ đang chờ đối chiếu hoàn tiền',
  DEMO_SELLER_PAYOUT: 'Khoản trả mô phỏng chưa xong',
  DEMO_REFUND: 'Khoản hoàn mô phỏng chưa xong',
  LEGACY_REFUND: 'Yêu cầu hoàn chưa hoàn tất',
}
export function BankProfileStatus({ profile }: { profile: BankProfile }) {
  return (
    <div className="space-y-2 text-sm" role="status">
      <p>
        {profile.canRegisterFirst
          ? 'Bạn được khai tài khoản lần đầu, kể cả khi đang có lịch sân.'
          : profile.canChange
            ? 'Bạn có thể thay tài khoản nhận tiền.'
            : 'Chưa thể thay, thêm tài khoản thay thế hoặc chọn tài khoản khác.'}
      </p>
      {profile.responsibilities.length > 0 && (
        <ul className="list-inside list-disc text-amber-900">
          {profile.responsibilities.map((item) => (
            <li key={`${item.kind}:${item.resourceId}`}>
              <Link
                className="underline"
                href={
                  item.kind.startsWith('PEER_')
                    ? `/customer/transfers/acquisitions/${item.resourceId}`
                    : item.kind === 'TRANSFER_LISTING'
                      ? `/transfers/${item.resourceId}`
                      : ['BOOKING', 'GROUP_BOOKING'].includes(item.kind)
                        ? `/customer/bookings/${item.resourceId}`
                        : '/customer/bookings'
                }
              >
                {reasons[item.kind] ?? 'Nghĩa vụ đang được đối chiếu'}
              </Link>
              {item.effectiveUntil &&
                ` · đến ${new Date(item.effectiveUntil).toLocaleString('vi-VN')}`}
            </li>
          ))}
        </ul>
      )}
      <p className="text-xs text-slate-600">
        Phân xử xong quyền sân sẽ gỡ khóa do tranh chấp. Lịch sân và khoản trả/hoàn chưa hoàn tất
        vẫn được kiểm tra riêng.
      </p>
    </div>
  )
}
