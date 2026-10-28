'use client'
import { useState } from 'react'
import Link from 'next/link'
import { RequireAuth } from '@/features/auth/require-auth'
import { BankDestinationForm } from '@/features/payments/bank-destination-form'
import { useAuth } from '@/lib/auth-context'

function Settings() {
  const [revision, setRevision] = useState('')
  return (
    <main className="mx-auto max-w-2xl space-y-5 p-6">
      <Link href="/customer/profile" className="text-emerald-700 underline">
        Hồ sơ cá nhân
      </Link>
      <h1 className="text-2xl font-bold">Ngân hàng nhận tiền chuyển nhượng</h1>
      <p>
        Bạn không cần khai tài khoản ngân hàng để đặt sân hoặc mua lại lượt đặt. Chỉ khi muốn đăng
        chuyển nhượng, bạn mới cần khai tài khoản để người mua chuyển 100% giá đã chốt trực tiếp cho
        bạn qua QR SePay.
      </p>
      <p className="text-sm text-slate-600">
        Bạn có thể khai trước tại đây hoặc ngay khi đăng chuyển nhượng. Thông tin đã chốt trong giao
        dịch cũ được giữ nguyên; quy tắc khóa thay đổi tài khoản vẫn áp dụng khi còn lịch sân hoặc
        nghĩa vụ đang xử lý.
      </p>
      <BankDestinationForm value={revision} onChange={setRevision} />
    </main>
  )
}
export default function BankSettingsPage() {
  const { user } = useAuth()
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Settings key={user?.id} />
    </RequireAuth>
  )
}
