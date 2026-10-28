'use client'
import { Camera } from 'lucide-react'
import Image from 'next/image'
import type { useProfile } from './use-profile'
export function ProfileAvatar({
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
  >
}) {
  const {
    avatarUrl,
    fullName,
    setAvatarUrl,
    getInitials,
    user,
    setShowAvatarPicker,
    showAvatarPicker,
  } = model
  return (
    <div className="relative group shrink-0">
      <div className="relative size-16 sm:size-20 overflow-hidden rounded-xl border border-slate-200 bg-slate-900 shadow-2xs dark:border-slate-800">
        {avatarUrl ? (
          <Image
            unoptimized
            width={80}
            height={80}
            src={avatarUrl}
            alt={fullName || 'Ảnh đại diện'}
            className="size-full object-cover"
            onError={() => setAvatarUrl('')}
          />
        ) : (
          <div className="flex size-full items-center justify-center font-bold text-white text-lg sm:text-xl">
            {getInitials(fullName || user?.fullName)}
          </div>
        )}
      </div>
      <button
        type="button"
        onClick={() => setShowAvatarPicker(!showAvatarPicker)}
        title="Đổi ảnh đại diện"
        className="absolute -bottom-1 -right-1 flex size-7 items-center justify-center rounded-lg border border-white bg-emerald-700 text-white shadow-xs transition-colors hover:bg-emerald-800"
      >
        <Camera size={13} strokeWidth={1.75} />
      </button>
    </div>
  )
}
