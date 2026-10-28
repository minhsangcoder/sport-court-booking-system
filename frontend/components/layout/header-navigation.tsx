'use client'
import { ArrowLeftRight, Briefcase, Calendar, CalendarCheck2, Search, Users } from 'lucide-react'
import Link from 'next/link'
import type { HeaderModel } from './use-header'
export function DesktopNavigation({ model }: { model: HeaderModel }) {
  const { user, isStaffOnly, isActive } = model
  const linkClass = (path: string) =>
    `flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs sm:text-[13px] transition-colors whitespace-nowrap ${
      isActive(path)
        ? 'bg-slate-100 font-semibold text-slate-900 dark:bg-slate-800 dark:text-white'
        : 'font-medium text-slate-600 hover:bg-slate-100/70 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800/70 dark:hover:text-white'
    }`
  return (
    <nav aria-label="Điều hướng chính" className="hidden md:flex items-center gap-1 min-w-0">
      {!isStaffOnly && (
        <Link href="/search" className={linkClass('/search')}>
          <Search size={14} strokeWidth={1.75} className="text-slate-400" />
          <span>Tìm sân</span>
        </Link>
      )}

      {!isStaffOnly && user?.roles.includes('CUSTOMER') && (
        <Link href="/transfers" className={linkClass('/transfers')}>
          <ArrowLeftRight size={14} strokeWidth={1.75} className="text-slate-400" />
          <span>Chuyển nhượng</span>
        </Link>
      )}

      {!isStaffOnly && user?.roles.includes('CUSTOMER') && (
        <Link href="/customer/groups" className={linkClass('/customer/groups')}>
          <Users size={14} strokeWidth={1.75} className="text-slate-400" />
          <span>Nhóm thể thao</span>
        </Link>
      )}

      {!isStaffOnly && user?.roles.includes('CUSTOMER') && (
        <Link href="/customer/bookings" className={linkClass('/customer/bookings')}>
          <Calendar size={14} strokeWidth={1.75} className="text-slate-400" />
          <span>Lịch đặt</span>
        </Link>
      )}

      {isStaffOnly && (
        <>
          <Link href="/staff/bookings" className={linkClass('/staff/bookings')}>
            <Briefcase size={14} strokeWidth={1.75} className="text-emerald-700" />
            <span>Kiểm tra lượt đặt</span>
          </Link>
          <Link href="/staff/counter-booking" className={linkClass('/staff/counter-booking')}>
            <CalendarCheck2 size={14} strokeWidth={1.75} className="text-slate-400" />
            <span>Đặt tại quầy</span>
          </Link>
        </>
      )}
    </nav>
  )
}
