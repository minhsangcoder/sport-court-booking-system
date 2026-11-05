'use client'
import type { useAccounts } from '@/features/admin/use-accounts'
import { Clock, UserCheck, UserX, Users } from 'lucide-react'
type Model = Pick<
  ReturnType<typeof useAccounts>,
  'stats' | 'totalUsers' | 'activeUsers' | 'restrictedUsers' | 'pendingUsers'
>
/** Whole-platform account counts by status; the four cards always add up to the total. */
export function AccountStats({ model }: { model: Model }) {
  const { stats, totalUsers, activeUsers, restrictedUsers, pendingUsers } = model
  const value = (n: number) => (stats.loading ? '…' : stats.error ? '—' : n)
  const cards = [
    {
      title: 'Tổng tài khoản',
      value: totalUsers,
      unit: 'tài khoản',
      note: 'Toàn bộ tài khoản trên nền tảng',
      icon: <Users size={18} />,
      tone: 'bg-blue-50 text-blue-600 dark:bg-blue-950 dark:text-blue-400',
      number: 'text-slate-900 dark:text-white',
    },
    {
      title: 'Đang hoạt động',
      value: activeUsers,
      unit: 'bình thường',
      note: 'Đăng nhập và sử dụng dịch vụ bình thường',
      icon: <UserCheck size={18} />,
      tone: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-400',
      number: 'text-emerald-600 dark:text-emerald-400',
    },
    {
      title: 'Bị khóa / Tạm ngừng',
      value: restrictedUsers,
      unit: 'hạn chế',
      note: 'Gồm khóa, tạm ngừng và vô hiệu hóa',
      icon: <UserX size={18} />,
      tone: 'bg-red-50 text-red-600 dark:bg-red-950 dark:text-red-400',
      number: 'text-red-600 dark:text-red-400',
    },
    {
      title: 'Chờ xác minh',
      value: pendingUsers,
      unit: 'chưa xác minh',
      note: 'Đã đăng ký nhưng chưa xác minh thư điện tử hoặc số điện thoại',
      icon: <Clock size={18} />,
      tone: 'bg-amber-50 text-amber-600 dark:bg-amber-950 dark:text-amber-400',
      number: 'text-amber-600 dark:text-amber-400',
    },
  ]
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {cards.map((c) => (
        <div
          key={c.title}
          className="p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
        >
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-slate-500">
              {c.title}
            </span>
            <div className={`flex size-9 items-center justify-center rounded-xl ${c.tone}`}>
              {c.icon}
            </div>
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className={`text-3xl font-extrabold ${c.number}`}>{value(c.value)}</span>
            <span className="text-xs text-slate-400">{c.unit}</span>
          </div>
          <p className="mt-2 text-xs text-slate-500">{c.note}</p>
        </div>
      ))}
    </div>
  )
}
