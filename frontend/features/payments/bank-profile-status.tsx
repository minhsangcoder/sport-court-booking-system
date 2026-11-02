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
    <div className="space-y-3 text-sm" role="status">
      <p className="text-slate-700 dark:text-slate-300">
        {profile.canRegisterFirst
          ? 'Bạn được khai tài khoản lần đầu, kể cả khi đang có lịch sân.'
          : profile.canChange
            ? 'Bạn có thể thay tài khoản nhận tiền.'
            : 'Chưa thể thay, thêm tài khoản thay thế hoặc chọn tài khoản khác.'}
      </p>
      {profile.responsibilities.length > 0 && (
        <ul className="space-y-1.5 rounded-2xl border border-amber-200/80 bg-amber-50/70 p-4 text-amber-950/85 dark:border-amber-500/20 dark:bg-amber-500/5 dark:text-amber-100/85">
          {profile.responsibilities.map((item) => (
            <li key={`${item.kind}:${item.resourceId}`}>
              <Link
                className="font-semibold underline underline-offset-2"
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
      {profile.responsibilities.length > 0 && (
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Tài khoản được giữ nguyên đến khi các mục trên kết thúc.
        </p>
      )}
    </div>
  )
}
