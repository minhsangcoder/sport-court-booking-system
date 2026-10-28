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
      <div className="min-h-screen bg-slate-50/70 pb-20 dark:bg-slate-950/40">
        {/* Cover Hero Banner */}
        <ProfileHero />

        {/* Floating Profile Overview Card */}
        <div className="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
          <ProfileIdentity model={model} />

          {/* Sub Navigation Tabs */}
          <div className="mt-8 flex items-center justify-between border-b border-slate-200 pb-3 dark:border-slate-800">
            <nav className="flex space-x-6">
              <Link
                href="/customer/profile/bank"
                className="pb-3 text-sm font-medium text-emerald-700"
              >
                Ngân hàng nhận tiền
              </Link>
              <Link
                href="/customer/profile"
                className="border-b-2 border-emerald-600 pb-3 text-sm font-bold text-emerald-600 flex items-center gap-2"
              >
                <User size={16} />
                Thông tin cá nhân
              </Link>
              <Link
                href="/customer/profile/contact"
                className="border-b-2 border-transparent pb-3 text-sm font-medium text-slate-500 hover:text-slate-800 hover:border-slate-300 dark:text-slate-400 dark:hover:text-slate-200 flex items-center gap-2 transition-colors"
              >
                <Key size={16} />
                Bảo mật & Liên hệ
              </Link>
              <Link
                href="/customer/bookings"
                className="border-b-2 border-transparent pb-3 text-sm font-medium text-slate-500 hover:text-slate-800 hover:border-slate-300 dark:text-slate-400 dark:hover:text-slate-200 flex items-center gap-2 transition-colors"
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
