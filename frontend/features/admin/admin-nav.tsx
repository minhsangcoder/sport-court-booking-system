'use client'
import { BannerPage } from '@/components/page-banner'
import Link from 'next/link'
import type { ComponentProps } from 'react'

const sections = [
  ['reports', 'Báo cáo'],
  ['accounts', 'Tài khoản'],
  ['owner-applications', 'Đăng ký chủ cơ sở'],
  ['facilities', 'Cơ sở'],
  ['bookings', 'Lượt đặt'],
  ['payments', 'Giao dịch'],
  ['sports', 'Môn thể thao'],
]

export function AdminNav({ active }: { active: string }) {
  return (
    <nav aria-label="Quản trị nền tảng" className="mb-6 flex flex-wrap gap-2">
      {sections.map(([key, label]) => (
        <Link
          key={key}
          href={`/admin/${key}`}
          aria-current={active === key ? 'page' : undefined}
          className={`whitespace-nowrap rounded-full px-4 py-2 text-sm font-semibold transition-colors ${
            active === key
              ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
              : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:ring-emerald-400 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700'
          }`}
        >
          {label}
        </Link>
      ))}
    </nav>
  )
}

/** Banner, section pills and content column shared by the admin list screens. */
export function AdminPage({
  active,
  children,
  ...page
}: ComponentProps<typeof BannerPage> & { active: string }) {
  return (
    <BannerPage eyebrow="Quản trị SportHub" {...page}>
      <AdminNav active={active} />
      {children}
    </BannerPage>
  )
}
