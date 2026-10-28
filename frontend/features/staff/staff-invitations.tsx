'use client'
import { Button } from '@/components/ui/button'
import { RequireAuth } from '@/features/auth/require-auth'
import { Card, Frame, Status, useMutation } from '@/features/integration/shared'
import { businessApi, type StaffInvitation } from '@/lib/business-api'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
const PERMISSION_LABELS: Record<string, string> = {
  BOOKING_READ: 'Xem danh sách lượt đặt',
  BOOKING_CREATE_COUNTER: 'Tạo lượt đặt tại quầy',
  BOOKING_PAYMENT_COUNTER: 'Ghi nhận thanh toán tại quầy',
  BOOKING_CHECK_IN: 'Xác nhận khách vào sân',
  BOOKING_REFUND: 'Xử lý khoản hoàn đã ghi nhận',
  TRANSFER_PAYOUT: 'Trả tiền cho người chuyển nhượng',
  SCHEDULE_READ: 'Xem lịch sân',
  SCHEDULE_MANAGE: 'Quản lý lịch sân',
  COURT_STATUS_MANAGE: 'Quản lý trạng thái sân',
  COURT_MAINTENANCE_MANAGE: 'Quản lý bảo trì sân',
}
export function StaffInvitations() {
  return (
    <RequireAuth>
      <Invitations />
    </RequireAuth>
  )
}
function Invitations() {
  const r = useApi<StaffInvitation[]>('/users/me/staff-invitations')
  const a = useMutation()
  return (
    <Frame title="Lời mời làm việc tại cơ sở">
      <Status loading={r.loading} error={r.error || a.error} empty={r.data?.length === 0} />
      {r.data?.map((i) => (
        <Card key={i.id} title={i.staffName}>
          <p>
            Cơ sở {i.facilityId.slice(0, 8)} · {displayLabel(i.state)}
          </p>
          <p className="text-sm text-slate-500">
            {i.permissions.map((p) => PERMISSION_LABELS[p] || p).join(' · ')} · Hết hạn{' '}
            {new Date(i.expiresAt).toLocaleString('vi-VN')}
          </p>
          {i.state === 'PENDING' && (
            <div className="flex gap-3">
              <Button
                disabled={a.busy}
                onClick={() => a.run(() => businessApi.invitation(i.id, 'accept'), r.reload)}
              >
                Đồng ý lời mời
              </Button>
              <Button
                variant="outline"
                disabled={a.busy}
                onClick={() => a.run(() => businessApi.invitation(i.id, 'decline'), r.reload)}
              >
                Từ chối
              </Button>
            </div>
          )}
          {i.state === 'ACCEPTED' && (
            <p className="text-amber-800">
              Đã đồng ý; chờ chủ cơ sở xác nhận liên kết. Đăng nhập lại sau khi được cấp quyền nhân
              viên.
            </p>
          )}
        </Card>
      ))}
    </Frame>
  )
}
