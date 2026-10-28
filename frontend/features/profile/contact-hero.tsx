'use client'
import { ArrowLeft } from 'lucide-react'
import Link from 'next/link'

export function ContactHero() {
  return (
    <div className="relative w-full border-b border-slate-800 bg-slate-950 py-6 sm:py-8">
      <div className="mx-auto flex max-w-6xl items-center justify-between px-4 sm:px-6 lg:px-8">
        <div className="text-white">
          <Link
            href="/customer/profile"
            className="inline-flex items-center gap-1.5 text-xs font-medium text-emerald-400 hover:text-emerald-300 transition-colors mb-1.5"
          >
            <ArrowLeft size={13} strokeWidth={1.75} />
            Quay lại Hồ sơ cá nhân
          </Link>
          <h1 className="text-xl font-bold tracking-tight sm:text-2xl">
            Bảo mật & Thông tin liên hệ
          </h1>
        </div>
      </div>
    </div>
  )
}
