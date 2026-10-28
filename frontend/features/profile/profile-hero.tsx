'use client'
import { ArrowRight, Key, UserCheck } from 'lucide-react'
import Link from 'next/link'

export function ProfileHero() {
  return (
    <div className="relative w-full border-b border-slate-800 bg-slate-950 py-6 sm:py-8">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 sm:px-6 lg:px-8">
        <div className="text-white">
          <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-[0.14em] text-emerald-400">
            <UserCheck size={13} strokeWidth={1.75} />
            Hồ sơ tài khoản SportHub
          </span>
          <h1 className="mt-1 text-xl font-bold tracking-tight sm:text-2xl">
            Cài đặt hồ sơ cá nhân
          </h1>
        </div>

        <Link
          href="/customer/profile/contact"
          className="hidden h-8 items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-3.5 text-xs font-semibold text-slate-200 transition-colors hover:border-slate-600 hover:bg-slate-800 hover:text-white sm:inline-flex"
        >
          <Key size={13} strokeWidth={1.75} className="text-emerald-400" />
          Bảo mật & Liên hệ
          <ArrowRight size={13} strokeWidth={1.75} />
        </Link>
      </div>
    </div>
  )
}
