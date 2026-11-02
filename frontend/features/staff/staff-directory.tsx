'use client'
import { StaffCreateForm } from '@/features/staff/staff-create-form'
import { StaffList } from '@/features/staff/staff-list'
import { useStaffDirectory } from '@/features/staff/use-staff-directory'
export function StaffManagement() {
  const model = useStaffDirectory()

  return (
    <div className="space-y-8">
      <div>
        <h2 className="text-2xl font-extrabold text-slate-900 dark:text-white">Nhân viên cơ sở</h2>
        <p className="mt-1 text-sm text-slate-500">
          Chủ cơ sở tạo tài khoản cho nhân viên để vận hành kiểm tra lượt đặt và xác nhận khách vào
          sân tại quầy.
        </p>
      </div>

      {/* Primary Action: Direct Staff Account Creation */}
      <StaffCreateForm model={model} />

      {/* Staff Accounts List */}
      <StaffList model={model} />
    </div>
  )
}
