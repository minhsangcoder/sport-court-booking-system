'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { useAccounts } from '@/features/admin/use-accounts'
import { displayLabel } from '@/lib/display-labels'
import { Filter, Search } from 'lucide-react'
export function AccountFilters({
  model,
}: {
  model: Pick<ReturnType<typeof useAccounts>, 'search' | 'reset'>
}) {
  const { search, reset } = model
  return (
    <div className="p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
      <form
        onSubmit={search}
        className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4 lg:items-end"
      >
        <div className="space-y-1.5">
          <label className="text-xs font-bold uppercase tracking-wider text-slate-500">
            Từ khóa tìm kiếm
          </label>
          <div className="relative">
            <Search
              size={16}
              className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400"
            />
            <Input
              name="q"
              placeholder="Họ tên, thư điện tử, SĐT..."
              className="pl-9 h-11 text-xs rounded-xl"
            />
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold uppercase tracking-wider text-slate-500">
            Trạng thái tài khoản
          </label>
          <select
            name="status"
            className="h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-xs font-medium text-slate-800 shadow-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <option value="">Tất cả trạng thái</option>
            {['ACTIVE', 'LOCKED', 'PENDING_VERIFICATION', 'SUSPENDED', 'DEACTIVATED'].map((s) => (
              <option key={s} value={s}>
                {displayLabel(s)}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold uppercase tracking-wider text-slate-500">
            Vai trò người dùng
          </label>
          <select
            name="role"
            className="h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-xs font-medium text-slate-800 shadow-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <option value="">Tất cả vai trò</option>
            {['CUSTOMER', 'OWNER', 'STAFF', 'ADMIN'].map((s) => (
              <option key={s} value={s}>
                {displayLabel(s)}
              </option>
            ))}
          </select>
        </div>

        <div className="flex gap-2">
          <Button
            type="submit"
            className="h-11 flex-1 bg-emerald-600 hover:bg-emerald-500 text-xs font-bold"
          >
            <Filter size={14} className="mr-1.5" />
            <span>Tra cứu ngay</span>
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={(e) => {
              e.currentTarget.form?.reset()
              reset()
            }}
            className="h-11 text-xs rounded-xl"
          >
            Đặt lại
          </Button>
        </div>
      </form>
    </div>
  )
}
