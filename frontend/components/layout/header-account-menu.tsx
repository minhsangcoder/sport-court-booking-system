'use client'
import {
  Briefcase,
  Building2,
  Calendar,
  CalendarCheck2,
  Heart,
  Landmark,
  LogOut,
  ShieldCheck,
  User,
  Users,
} from 'lucide-react'
import Link from 'next/link'
import type { HeaderModel } from './use-header'
import { displayLabel } from '@/lib/display-labels'
export function HeaderAccountMenu({ model }: { model: HeaderModel }) {
  const { user, isStaffOnly, busy, logout } = model
  if (!user) return null
  return (
    <div className="absolute right-0 mt-1.5 w-60 rounded-xl border border-slate-200 bg-white p-1.5 shadow-lg dark:border-slate-800 dark:bg-slate-900 z-50">
      {/* User Profile Header */}
      <div className="border-b border-slate-100 px-2.5 py-2 dark:border-slate-800">
        <p className="text-xs font-semibold text-slate-900 dark:text-white truncate">
          {user.fullName}
        </p>
        <p className="text-[11px] text-slate-500 truncate">{user.email || user.phone}</p>
        <div className="mt-1.5 flex flex-wrap gap-1">
          {user.roles.map((r) => (
            <span
              key={r}
              className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-[10px] font-medium text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300"
            >
              {displayLabel(r)}
            </span>
          ))}
        </div>
      </div>

      {/* Customer Links */}
      {!isStaffOnly && (
        <div className="py-1 text-xs font-medium text-slate-700 dark:text-slate-300">
          <Link
            href="/customer/bookings"
            className="flex items-center gap-2 rounded-md px-2.5 py-1.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <Calendar size={14} className="text-slate-400" />
            <span>Lịch đặt của tôi</span>
          </Link>
          <Link
            href="/customer/groups"
            className="flex items-center gap-2 rounded-md px-2.5 py-1.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <Users size={14} className="text-slate-400" />
            <span>Hội nhóm thể thao</span>
          </Link>
          <Link
            href="/customer/favorites"
            className="flex items-center gap-2 rounded-md px-2.5 py-1.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <Heart size={14} className="text-slate-400" />
            <span>Sân yêu thích</span>
          </Link>
          <Link
            href="/customer/profile"
            className="flex items-center gap-2 rounded-md px-2.5 py-1.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <User size={14} className="text-slate-400" />
            <span>Hồ sơ cá nhân</span>
          </Link>
          {user.roles.includes('CUSTOMER') && (
            <Link
              href="/customer/profile/bank"
              className="flex items-center gap-2 rounded-md px-2.5 py-1.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
            >
              <Landmark size={14} className="text-slate-400" />
              <span>Ngân hàng nhận tiền chuyển nhượng</span>
            </Link>
          )}
        </div>
      )}

      {/* Staff Dedicated Links */}
      {isStaffOnly && (
        <div className="py-1 text-xs font-medium text-slate-700 dark:text-slate-300">
          <Link
            href="/staff/bookings"
            className="flex items-center gap-2 rounded-md px-2.5 py-1.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800 font-semibold text-slate-900 dark:text-white"
          >
            <Briefcase size={14} className="text-emerald-700" />
            <span>Kiểm tra lượt đặt</span>
          </Link>
          <Link
            href="/staff/counter-booking"
            className="flex items-center gap-2 rounded-md px-2.5 py-1.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <CalendarCheck2 size={14} className="text-slate-400" />
            <span>Đặt tại quầy</span>
          </Link>
        </div>
      )}

      {/* Portals Divider */}
      {(user.roles.includes('OWNER') ||
        user.roles.includes('ADMIN') ||
        (user.roles.includes('STAFF') && !isStaffOnly)) && (
        <div className="border-t border-slate-100 py-1 dark:border-slate-800">
          {user.roles.includes('OWNER') && (
            <>
              <Link
                href="/owner/facilities"
                className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800"
              >
                <Building2 size={14} className="text-emerald-700" />
                <span>Cơ sở của tôi</span>
              </Link>
              <Link
                href="/owner/bookings"
                className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800"
              >
                <Briefcase size={14} className="text-emerald-700" />
                <span>Lịch đặt & Check-in khách</span>
              </Link>
              <Link
                href="/owner/counter-booking"
                className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800"
              >
                <CalendarCheck2 size={14} className="text-emerald-700" />
                <span>Đặt tại quầy cho khách</span>
              </Link>
            </>
          )}
          {user.roles.includes('STAFF') && !isStaffOnly && (
            <Link
              href="/staff/bookings"
              className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800"
            >
              <Briefcase size={14} className="text-slate-500" />
              <span>Kiểm tra lượt đặt</span>
            </Link>
          )}
          {user.roles.includes('ADMIN') && (
            <Link
              href="/admin/accounts"
              className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800"
            >
              <ShieldCheck size={14} className="text-emerald-700" />
              <span>Bảng điều khiển quản trị viên</span>
            </Link>
          )}
        </div>
      )}

      {/* Logout Button */}
      <div className="border-t border-slate-100 pt-1 dark:border-slate-800">
        <button
          type="button"
          onClick={logout}
          disabled={busy}
          className="flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-xs font-medium text-red-600 transition-colors hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-950/50 cursor-pointer"
        >
          <LogOut size={14} />
          <span>{busy ? 'Đang đăng xuất…' : 'Đăng xuất'}</span>
        </button>
      </div>
    </div>
  )
}
