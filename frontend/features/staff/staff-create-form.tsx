'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Status } from '@/features/integration/shared'
import { PERMISSION_DESCRIPTIONS, PERMISSION_LABELS } from '@/features/staff/staff-permissions.data'
import type { useStaffDirectory } from '@/features/staff/use-staff-directory'
import { Check, Eye, EyeOff, UserPlus } from 'lucide-react'
export function StaffCreateForm({
  model,
}: {
  model: Pick<
    ReturnType<typeof useStaffDirectory>,
    | 'createSuccess'
    | 'a'
    | 'handleCreateStaffAccount'
    | 'fullName'
    | 'setFullName'
    | 'email'
    | 'setEmail'
    | 'phone'
    | 'setPhone'
    | 'showPassword'
    | 'password'
    | 'setPassword'
    | 'setShowPassword'
    | 'selectAllPermissions'
    | 'clearAllPermissions'
    | 'availableCatalog'
    | 'selectedPermissions'
    | 'togglePermission'
  >
}) {
  const {
    createSuccess,
    a,
    handleCreateStaffAccount,
    fullName,
    setFullName,
    email,
    setEmail,
    phone,
    setPhone,
    showPassword,
    password,
    setPassword,
    setShowPassword,
    selectAllPermissions,
    clearAllPermissions,
    availableCatalog,
    selectedPermissions,
    togglePermission,
  } = model
  return (
    <section className="rounded-2xl border border-emerald-100 bg-gradient-to-br from-emerald-50/60 via-white to-slate-50/50 p-6 shadow-sm dark:border-emerald-950/40 dark:bg-slate-900/60">
      <div className="flex items-center gap-2.5 pb-4 border-b border-emerald-100 dark:border-emerald-950/40">
        <div className="flex size-9 items-center justify-center rounded-xl bg-emerald-600 text-white shadow-sm">
          <UserPlus size={18} />
        </div>
        <div>
          <h3 className="text-lg font-bold text-slate-900 dark:text-white">
            Tạo tài khoản nhân viên mới
          </h3>
          <p className="text-xs text-slate-500">
            Nhân viên được tạo sẽ có quyền nhân viên, đăng nhập vào thẳng màn hình Kiểm tra lượt
            đặt.
          </p>
        </div>
      </div>

      {createSuccess && (
        <div className="mt-4 flex items-center gap-2 rounded-2xl bg-emerald-50 p-3.5 text-sm font-semibold text-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300">
          <Check size={18} className="text-emerald-600 shrink-0" />
          <span>{createSuccess}</span>
        </div>
      )}

      <Status error={a.error} />

      <form onSubmit={handleCreateStaffAccount} className="mt-5 space-y-5">
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="grid gap-1.5 text-sm font-semibold text-slate-700 dark:text-slate-300">
            Họ và tên nhân viên *
            <Input
              placeholder="Ví dụ: Nguyễn Văn A"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              required
              minLength={2}
              maxLength={255}
              disabled={a.busy}
            />
          </label>

          <label className="grid gap-1.5 text-sm font-semibold text-slate-700 dark:text-slate-300">
            Thư điện tử đăng nhập *
            <Input
              type="email"
              placeholder="nhanvien@sporthub.local"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              disabled={a.busy}
            />
          </label>

          <label className="grid gap-1.5 text-sm font-semibold text-slate-700 dark:text-slate-300">
            Số điện thoại (tùy chọn)
            <Input
              placeholder="0912345678"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              pattern="\+?[0-9]{9,15}"
              disabled={a.busy}
            />
          </label>

          <label className="grid gap-1.5 text-sm font-semibold text-slate-700 dark:text-slate-300">
            Mật khẩu đăng nhập ban đầu *
            <div className="relative">
              <Input
                type={showPassword ? 'text' : 'password'}
                placeholder="Tối thiểu 6 ký tự"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                minLength={6}
                maxLength={72}
                disabled={a.busy}
                className="pr-10"
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
          </label>
        </div>

        <div>
          <div className="flex items-center justify-between mb-2.5">
            <span className="text-sm font-semibold text-slate-800 dark:text-slate-200">
              Phân quyền cho nhân viên tại cơ sở:
            </span>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={selectAllPermissions}
                className="text-xs font-semibold text-emerald-600 hover:underline"
              >
                Chọn tất cả
              </button>
              <span className="text-xs text-slate-300">|</span>
              <button
                type="button"
                onClick={clearAllPermissions}
                className="text-xs font-semibold text-slate-500 hover:underline"
              >
                Bỏ chọn
              </button>
            </div>
          </div>

          <div className="grid gap-2.5 sm:grid-cols-2 lg:grid-cols-3 rounded-2xl border border-slate-200 bg-white p-3.5 dark:border-slate-800 dark:bg-slate-900">
            {availableCatalog.map((code) => {
              const checked = selectedPermissions.includes(code)
              return (
                <label
                  key={code}
                  className={`flex items-start gap-2.5 rounded-lg border p-2.5 text-xs font-medium cursor-pointer transition-all ${
                    checked
                      ? 'border-emerald-300 bg-emerald-50/60 text-emerald-950 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200'
                      : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300'
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => togglePermission(code)}
                    className="mt-0.5 size-4 rounded border-slate-300 text-emerald-600 focus:ring-emerald-500"
                  />
                  <div>
                    <p className="font-bold">{PERMISSION_LABELS[code] || code}</p>
                    {PERMISSION_DESCRIPTIONS[code] && (
                      <p className="mt-0.5 text-[11px] text-slate-500 leading-snug">
                        {PERMISSION_DESCRIPTIONS[code]}
                      </p>
                    )}
                    <p className="text-[10px] text-slate-400 font-mono mt-0.5">{code}</p>
                  </div>
                </label>
              )
            })}
          </div>
        </div>

        <div className="flex justify-end pt-2">
          <Button
            type="submit"
            disabled={
              a.busy ||
              !fullName.trim() ||
              !email.trim() ||
              !password ||
              selectedPermissions.length === 0
            }
            className="gap-2 bg-emerald-600 hover:bg-emerald-600 text-white font-bold px-6 py-2.5 rounded-full shadow-md"
          >
            <UserPlus size={16} />
            <span>{a.busy ? 'Đang tạo tài khoản…' : 'Tạo tài khoản nhân viên'}</span>
          </Button>
        </div>
      </form>
    </section>
  )
}
