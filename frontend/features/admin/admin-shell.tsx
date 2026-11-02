'use client'
import { AdminPage } from './admin-nav'

export function AdminShell({ active, children }: { active: string; children: React.ReactNode }) {
  return (
    <AdminPage
      active={active}
      title="Tài khoản người dùng"
      description="Tra cứu, phân quyền và theo dõi trạng thái tài khoản trên nền tảng."
    >
      {children}
    </AdminPage>
  )
}
