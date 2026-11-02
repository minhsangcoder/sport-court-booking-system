'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { AvatarUploader } from '@/features/profile/avatar-uploader'
import type { useProfile } from '@/features/profile/use-profile'
import { AlertCircle, Calendar, Camera, CheckCircle2, RefreshCw, Save, User } from 'lucide-react'
export function ProfileForm({
  model,
}: {
  model: Pick<
    ReturnType<typeof useProfile>,
    | 'message'
    | 'error'
    | 'handleSave'
    | 'fullName'
    | 'setFullName'
    | 'busy'
    | 'dateOfBirth'
    | 'setDateOfBirth'
    | 'setShowAvatarPicker'
    | 'setAvatarUrl'
    | 'user'
  >
}) {
  const {
    message,
    error,
    handleSave,
    fullName,
    setFullName,
    busy,
    dateOfBirth,
    setDateOfBirth,
    setShowAvatarPicker,
    setAvatarUrl,
    user,
  } = model
  return (
    <div className="lg:col-span-2">
      <div className="p-6 sm:p-8 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
        <div className="flex items-center justify-between border-b border-slate-100 pb-5 dark:border-slate-800">
          <div>
            <h3 className="text-lg font-bold text-slate-900 dark:text-white">Thông tin cơ bản</h3>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              Cập nhật họ tên, ảnh đại diện và ngày sinh hiển thị trong các trận đấu và lịch đặt
              sân.
            </p>
          </div>
          <span className="rounded-lg bg-emerald-50 px-2.5 py-1 text-xs font-semibold text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-300">
            Cá nhân
          </span>
        </div>

        {/* Status Banners */}
        {message && (
          <div className="mt-5 flex items-center gap-3 rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-sm font-medium text-emerald-800 dark:border-emerald-800/60 dark:bg-emerald-950/40 dark:text-emerald-200 animate-in fade-in">
            <CheckCircle2 size={18} className="shrink-0 text-emerald-600" />
            <span>{message}</span>
          </div>
        )}

        {error && (
          <div className="mt-5 flex items-center gap-3 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm font-medium text-rose-800 dark:border-rose-800/60 dark:bg-rose-950/40 dark:text-rose-200 animate-in fade-in">
            <AlertCircle size={18} className="shrink-0 text-rose-600" />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSave} className="mt-6 space-y-6">
          {/* Full Name Field */}
          <div className="space-y-2">
            <label className="text-sm font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
              <User size={15} className="text-emerald-600" />
              Họ và tên <span className="text-rose-500">*</span>
            </label>
            <Input
              name="fullName"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              placeholder="Nhập họ và tên đầy đủ của bạn"
              minLength={2}
              maxLength={120}
              required
              disabled={busy}
              className="h-11 rounded-xl border-slate-200 bg-slate-50/50 focus:bg-white dark:border-slate-800 dark:bg-slate-900 text-sm"
            />
            <p className="text-xs text-slate-400">
              Tên này sẽ hiển thị trên lịch đặt sân, nhóm chơi và hóa đơn.
            </p>
          </div>

          {/* Date of Birth Field */}
          <div className="space-y-2">
            <label className="text-sm font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
              <Calendar size={15} className="text-emerald-600" />
              Ngày sinh
            </label>
            <Input
              name="dateOfBirth"
              type="date"
              value={dateOfBirth}
              onChange={(e) => setDateOfBirth(e.target.value)}
              disabled={busy}
              className="h-11 rounded-xl border-slate-200 bg-slate-50/50 focus:bg-white dark:border-slate-800 dark:bg-slate-900 text-sm"
            />
            <p className="text-xs text-slate-400">
              Giúp cá nhân hóa trải nghiệm và nhận ưu đãi đặt sân ngày sinh nhật.
            </p>
          </div>

          {/* Avatar */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
                <Camera size={15} className="text-emerald-600" />
                Ảnh đại diện
              </span>
              <button
                type="button"
                onClick={() => setShowAvatarPicker(true)}
                className="text-xs font-semibold text-emerald-600 hover:underline"
              >
                Chọn mẫu có sẵn
              </button>
            </div>
            <AvatarUploader />
          </div>

          {/* Actions Bar */}
          <div className="flex flex-col-reverse gap-3 pt-4 sm:flex-row sm:items-center sm:justify-end border-t border-slate-100 dark:border-slate-800">
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() => {
                if (user) {
                  setFullName(user.fullName || '')
                  setDateOfBirth(user.dateOfBirth || '')
                  setAvatarUrl(user.avatarUrl || '')
                }
              }}
              className="h-11 rounded-xl font-medium"
            >
              Đặt lại
            </Button>
            <Button
              type="submit"
              disabled={busy}
              className="h-11 px-6 font-semibold text-white shadow-md shadow-emerald-600/20 transition-all"
            >
              {busy ? (
                <>
                  <RefreshCw size={16} className="animate-spin mr-2" />
                  Đang lưu thay đổi…
                </>
              ) : (
                <>
                  <Save size={16} className="mr-2" />
                  Lưu thay đổi hồ sơ
                </>
              )}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
