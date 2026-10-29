'use client'
import {
  BarChart3,
  CheckCircle2,
  Clock,
  FileText,
  Grid,
  Layers,
  LayoutDashboard,
  Repeat,
  Settings,
  Tag,
  Users,
  Wrench,
} from 'lucide-react'
export function facilityNavigation(applicationId?: string) {
  // Navigation Items with Icons and Groups
  const navGroups = applicationId
    ? [
        {
          group: 'HỒ SƠ THẨM ĐỊNH',
          items: [
            { suffix: '', label: 'Thông tin và ảnh', icon: LayoutDashboard },
            { suffix: '/courts', label: 'Danh sách sân', icon: Layers },
            { suffix: '/court-types', label: 'Loại sân', icon: Grid },
            { suffix: '/schedule', label: 'Lịch mở cửa', icon: Clock },
            { suffix: '/pricing', label: 'Bảng giá', icon: Tag },
            { suffix: '/documents', label: 'Tài liệu pháp lý', icon: FileText },
            { suffix: '/edit', label: 'Chỉnh sửa hồ sơ', icon: Settings },
          ],
        },
      ]
    : [
        {
          group: 'TỔNG QUAN',
          items: [
            { suffix: '', label: 'Tổng quan cơ sở', icon: LayoutDashboard },
            { suffix: '/review', label: 'Kiểm duyệt & Trạng thái', icon: CheckCircle2 },
          ],
        },
        {
          group: 'SÂN & LỊCH',
          items: [
            { suffix: '/courts', label: 'Quản lý sân', icon: Layers },
            { suffix: '/court-types', label: 'Loại sân', icon: Grid },
            { suffix: '/schedule', label: 'Lịch hoạt động', icon: Clock },
            { suffix: '/pricing', label: 'Bảng giá sân', icon: Tag },
            { suffix: '/maintenance', label: 'Bảo trì định kỳ', icon: Wrench },
          ],
        },
        {
          group: 'VẬN HÀNH & ĐỘI NGŨ',
          items: [
            { suffix: '/staff', label: 'Nhân viên cơ sở', icon: Users },
            { suffix: '/documents', label: 'Tài liệu & Giấy phép', icon: FileText },
            { suffix: '/transfer-policy', label: 'Chính sách chuyển nhượng', icon: Repeat },
            { suffix: '/peer-cases', label: 'Tranh chấp quyền sân', icon: CheckCircle2 },
            { suffix: '/payment-channel', label: 'Kênh nhận tiền', icon: Settings },
            { suffix: '/reports', label: 'Báo cáo & Doanh thu', icon: BarChart3 },
          ],
        },
        {
          group: 'CÀI ĐẶT',
          items: [{ suffix: '/edit', label: 'Cập nhật thông tin', icon: Settings }],
        },
      ]

  return navGroups
}
