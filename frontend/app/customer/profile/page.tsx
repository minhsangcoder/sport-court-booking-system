'use client'
import { RequireAuth } from '@/features/auth/require-auth'
import { ProfileForm } from '@/features/profile/profile-form'
import { ProfileHero } from '@/features/profile/profile-hero'
import { ProfileIdentity } from '@/features/profile/profile-identity'
import { ProfileSecurity } from '@/features/profile/profile-security'
import { useProfile } from '@/features/profile/use-profile'
import { Clock, Key, User } from 'lucide-react'
import Link from 'next/link'
export default function ProfilePage() {
  const model = useProfile()

  return (
    <RequireAuth>
      <div className="min-h-screen pb-20">
        {/* Cover Hero Banner */}
        <ProfileHero />

        {/* Floating Profile Overview Card */}
        <div className="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
          <ProfileIdentity model={model} />

          {/* Sub Navigation Tabs */}
          <div className="mt-7">
            <nav className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
              <Link
                href="/customer/profile/bank"
                className="flex items-center gap-2 whitespace-nowrap rounded-full bg-white px-4 py-2 text-sm font-semibold text-slate-600 ring-1 ring-slate-200 transition-colors hover:ring-emerald-400 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700"
              >
                Ngân hàng nhận tiền
              </Link>
              <Link
                href="/customer/profile"
                className="flex items-center gap-2 whitespace-nowrap rounded-full bg-slate-900 px-4 py-2 text-sm font-semibold text-white dark:bg-white dark:text-slate-900"
              >
                <User size={16} />
                Thông tin cá nhân
              </Link>
              <Link
                href="/customer/profile/contact"
                className="flex items-center gap-2 whitespace-nowrap rounded-full bg-white px-4 py-2 text-sm font-semibold text-slate-600 ring-1 ring-slate-200 transition-colors hover:ring-emerald-400 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700"
              >
                <Key size={16} />
                Bảo mật & Liên hệ
              </Link>
              <Link
                href="/customer/bookings"
                className="flex items-center gap-2 whitespace-nowrap rounded-full bg-white px-4 py-2 text-sm font-semibold text-slate-600 ring-1 ring-slate-200 transition-colors hover:ring-emerald-400 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700"
              >
                <Clock size={16} />
                Lịch sử đặt sân
              </Link>
            </nav>
          </div>

          {/* Main Content Grid: Form (Left) & Sidebar Widgets (Right) */}
          <div className="mt-8 grid grid-cols-1 gap-8 lg:grid-cols-3">
            {/* Left 2 Cols: Profile Form */}
            <ProfileForm model={model} />

            {/* Right 1 Col: Quick Info & Security Sidebar */}
            <ProfileSecurity model={model} />
          </div>
        </div>
      </div>
    </RequireAuth>
  )
}
