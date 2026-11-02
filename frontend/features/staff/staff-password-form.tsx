'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { useStaffProfile } from '@/features/staff/use-staff-profile'
import { Eye, EyeOff } from 'lucide-react'
export function StaffPasswordForm({
  model,
}: {
  model: Pick<
    ReturnType<typeof useStaffProfile>,
    | 'handleResetPassword'
    | 'showNewPassword'
    | 'newPassword'
    | 'setNewPassword'
    | 'a'
    | 'setShowNewPassword'
  >
}) {
  const {
    handleResetPassword,
    showNewPassword,
    newPassword,
    setNewPassword,
    a,
    setShowNewPassword,
  } = model
  return (
    <form
      onSubmit={handleResetPassword}
      className="mt-3 rounded-2xl border border-amber-200 bg-amber-50/50 p-3.5 dark:border-amber-900/50 dark:bg-amber-950/20"
    >
      <p className="text-xs font-bold text-amber-900 dark:text-amber-300 mb-2">
        Đặt lại mật khẩu mới cho nhân viên:
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-[220px]">
          <Input
            type={showNewPassword ? 'text' : 'password'}
            placeholder="Mật khẩu mới (tối thiểu 6 ký tự)"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            required
            minLength={6}
            maxLength={72}
            disabled={a.busy}
            className="h-9 text-xs pr-8 bg-white"
          />
          <button
            type="button"
            onClick={() => setShowNewPassword(!showNewPassword)}
            className="absolute right-2 top-1/2 -translate-y-1/2 text-slate-400"
          >
            {showNewPassword ? <EyeOff size={14} /> : <Eye size={14} />}
          </button>
        </div>
        <Button
          type="submit"
          size="sm"
          disabled={a.busy || newPassword.length < 6}
          className="h-9 text-xs"
        >
          Lưu mật khẩu mới
        </Button>
      </div>
    </form>
  )
}
