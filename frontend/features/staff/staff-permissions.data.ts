export const PERMISSION_LABELS: Record<string, string> = {
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
export const PERMISSION_DESCRIPTIONS: Record<string, string> = {
  BOOKING_READ: 'Xem danh sách và chi tiết các lượt đặt tại cơ sở',
  BOOKING_CREATE_COUNTER: 'Tạo lượt đặt cho khách đến đặt sân trực tiếp tại quầy',
  BOOKING_PAYMENT_COUNTER: 'Ghi nhận thu tiền mặt, chuyển khoản và người thu tiền tại quầy',
  BOOKING_CHECK_IN: 'Xác nhận khách vào sân bằng mã QR hoặc mã lượt đặt',
  BOOKING_REFUND:
    'Thực hiện khoản hoàn đã ghi nhận; không cấp quyền hủy lượt đặt, đổi người nhận hoặc xem báo cáo doanh thu',
  TRANSFER_PAYOUT:
    'Trả phần tiền còn lại cho người bán sau phí cơ sở; không đổi phí, số tiền, người nhận hoặc xem doanh thu',
  SCHEDULE_READ: 'Xem lịch sân và khung giờ hoạt động',
  SCHEDULE_MANAGE: 'Khóa / mở lịch và chỉnh sửa khung giờ',
  COURT_STATUS_MANAGE: 'Bật / tắt trạng thái hoạt động sân',
  COURT_MAINTENANCE_MANAGE: 'Lên lịch và kết thúc bảo trì sân',
}
export const DEFAULT_STAFF_PERMISSIONS = [
  'BOOKING_READ',
  'BOOKING_CREATE_COUNTER',
  'BOOKING_PAYMENT_COUNTER',
  'BOOKING_CHECK_IN',
  'SCHEDULE_READ',
]
