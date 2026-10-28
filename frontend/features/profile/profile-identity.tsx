'use client'
import type { useProfile } from '@/features/profile/use-profile'
import { PRESET_AVATARS } from '@/features/profile/use-profile-shared'
import { Building2, CalendarCheck2, CheckCircle2, Shield, ShieldCheck } from 'lucide-react'
import Image from 'next/image'
import Link from 'next/link'
import { ProfileAvatar } from './profile-avatar'

export function ProfileIdentity({
  model,
}: {
  model: Pick<
    ReturnType<typeof useProfile>,
    | 'avatarUrl'
    | 'fullName'
    | 'setAvatarUrl'
    | 'getInitials'
    | 'user'
    | 'setShowAvatarPicker'
    | 'showAvatarPicker'
    | 'getRoleBadge'
  >
}) {
  const {
    avatarUrl,
    fullName,
    setAvatarUrl,
    user,
    setShowAvatarPicker,
    showAvatarPicker,
    getRoleBadge,
  } = model
  return (
    <div className="mt-6 relative z-10 rounded-xl border border-slate-200 bg-white p-5 shadow-2xs dark:border-slate-800 dark:bg-slate-900 sm:p-6">
      <div className="flex flex-col gap-5 md:flex-row md:items-center md:justify-between">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
          <ProfileAvatar model={model} />

          <div className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-lg font-bold text-slate-900 dark:text-white sm:text-xl">
                {fullName || user?.fullName || 'Người dùng SportHub'}
              </h2>
              {user?.status === 'ACTIVE' && (
                <span title="Tài khoản đã kích hoạt" className="flex items-center text-emerald-700">
                  <CheckCircle2
                    size={16}
                    strokeWidth={1.75}
                    className="fill-emerald-50 text-emerald-700 dark:fill-emerald-950"
                  />
                </span>
              )}
            </div>

            <p className="text-xs text-slate-500 dark:text-slate-400">
              {user?.email || user?.phone || 'Chưa cung cấp thông tin liên hệ'}
            </p>

            <div className="flex flex-wrap items-center gap-1.5 pt-1">
              {user?.roles?.map((role) => {
                const badge = getRoleBadge(role)
                const IconComponent = badge.icon
                return (
                  <span
                    key={role}
                    className={`inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-[11px] font-semibold ${badge.className}`}
                  >
                    <IconComponent size={11} />
                    {badge.label}
                  </span>
                )
              })}
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-4 md:border-t-0 md:pt-0">
          <Link
            href="/customer/bookings"
            className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 text-xs font-semibold text-slate-700 shadow-2xs transition-colors hover:bg-slate-50 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-200"
          >
            <CalendarCheck2 size={14} strokeWidth={1.75} className="text-emerald-700" />
            Lịch đặt của tôi
          </Link>

          {user?.roles?.includes('ADMIN') && (
            <Link
              href="/admin"
              className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-slate-900 px-3 text-xs font-semibold text-white shadow-2xs transition-colors hover:bg-slate-800"
            >
              <ShieldCheck size={14} strokeWidth={1.75} className="text-emerald-400" />
              Trang quản trị viên
            </Link>
          )}

          {user?.roles?.includes('OWNER') && (
            <Link
              href="/owner"
              className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-emerald-700 px-3 text-xs font-semibold text-white shadow-2xs transition-colors hover:bg-emerald-800"
            >
              <Building2 size={14} strokeWidth={1.75} />
              Kênh Chủ sân
            </Link>
          )}

          {user?.roles?.includes('STAFF') && (
            <Link
              href="/staff"
              className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-300 bg-slate-50 px-3 text-xs font-semibold text-slate-800 shadow-2xs transition-colors hover:bg-slate-100"
            >
              <Shield size={14} strokeWidth={1.75} className="text-slate-600" />
              Kênh Nhân viên
            </Link>
          )}
        </div>
      </div>

      {showAvatarPicker && (
        <div className="mt-5 border-t border-slate-100 pt-4 dark:border-slate-800">
          <div className="flex items-center justify-between pb-3">
            <div className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              Chọn nhanh ảnh đại diện
            </div>
            <button
              type="button"
              onClick={() => setShowAvatarPicker(false)}
              className="text-xs text-slate-500 hover:text-slate-800 dark:hover:text-slate-200"
            >
              Đóng lại
            </button>
          </div>
          <div className="grid grid-cols-3 gap-2.5 sm:grid-cols-6">
            {PRESET_AVATARS.map((preset) => (
              <button
                key={preset.name}
                type="button"
                onClick={() => {
                  setAvatarUrl(preset.url)
                  setShowAvatarPicker(false)
                }}
                className={`group relative flex flex-col items-center gap-1.5 rounded-lg border p-2 text-center transition-colors ${
                  avatarUrl === preset.url
                    ? 'border-emerald-700 bg-emerald-50/50 ring-1 ring-emerald-700/20'
                    : 'border-slate-200 hover:border-slate-300 hover:bg-slate-50'
                }`}
              >
                <Image
                  unoptimized
                  width={40}
                  height={40}
                  src={preset.url}
                  alt={preset.name}
                  className="size-10 rounded-full object-cover shadow-2xs"
                />
                <span className="text-[11px] font-medium text-slate-600 dark:text-slate-300 truncate w-full">
                  {preset.name}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
