'use client'
import {
  ArrowLeftRight,
  Briefcase,
  Building2,
  Calendar,
  CalendarCheck2,
  Heart,
  Landmark,
  LayoutGrid,
  LogOut,
  Search,
  ShieldCheck,
  User,
  Users,
} from 'lucide-react'
import Link from 'next/link'
import type { HeaderModel } from './use-header'
import { displayLabel } from '@/lib/display-labels'
export function HeaderMobileMenu({ model }: { model: HeaderModel }) {
  const { user, isStaffOnly, busy, setMobileMenuOpen, logout, isActive } = model
  const itemClass = (path?: string) =>
    `flex items-center gap-2.5 rounded-lg px-3 py-2 text-xs font-medium transition-colors ${
      path && isActive(path)
        ? 'bg-slate-100 font-semibold text-slate-900 dark:bg-slate-800 dark:text-white'
        : 'text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800'
    }`
  return (
    <div className="border-b border-slate-200 bg-white p-3.5 shadow-lg md:hidden dark:border-slate-800 dark:bg-slate-900 max-h-[calc(100vh-56px)] overflow-y-auto">
      {!user ? (
        <div className="mb-3 rounded-xl border border-slate-200 bg-slate-50 p-3.5 dark:border-slate-800 dark:bg-slate-800/50">
          <div className="flex items-center gap-2.5">
            <div className="flex size-8 shrink-0 items-center justify-center rounded-md bg-emerald-800 text-white">
              <LayoutGrid size={15} />
            </div>
            <div>
              <h3 className="text-xs font-semibold text-slate-900 dark:text-white">
                Tài khoản SportHub
              </h3>
              <p className="text-[11px] text-slate-500 dark:text-slate-400">
                Đặt sân, chia tiền nhóm và quản lý vé QR
              </p>
            </div>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2">
            <Link
              href="/login"
              onClick={() => setMobileMenuOpen(false)}
              className="flex h-8 items-center justify-center rounded-md border border-slate-300 bg-white text-xs font-medium text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
            >
              Đăng nhập
            </Link>
            <Link
              href="/register"
              onClick={() => setMobileMenuOpen(false)}
              className="flex h-8 items-center justify-center rounded-md bg-emerald-700 text-xs font-semibold text-white hover:bg-emerald-800"
            >
              Đăng ký
            </Link>
          </div>
        </div>
      ) : (
        <div className="mb-3 rounded-xl border border-slate-200 bg-slate-50 p-3 dark:border-slate-800 dark:bg-slate-800/60">
          <div className="flex items-center gap-2.5">
            <div className="flex size-8 items-center justify-center rounded-md bg-slate-900 text-xs font-semibold text-white shrink-0 dark:bg-emerald-700">
              {user.fullName ? user.fullName.charAt(0).toUpperCase() : 'U'}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-xs font-semibold text-slate-900 dark:text-white">
                {user.fullName}
              </p>
              <p className="truncate text-[11px] text-slate-500 dark:text-slate-400">
                {user.email || user.phone}
              </p>
            </div>
          </div>
          <div className="mt-2 flex flex-wrap gap-1">
            {user.roles.map((r) => (
              <span
                key={r}
                className="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] font-medium text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300"
              >
                {displayLabel(r)}
              </span>
            ))}
          </div>
        </div>
      )}

      <nav className="space-y-0.5">
        {isStaffOnly ? (
          <>
            <Link
              href="/staff/bookings"
              onClick={() => setMobileMenuOpen(false)}
              className={itemClass('/staff/bookings')}
            >
              <Briefcase size={15} className="text-emerald-700" />
              <span>Kiểm tra lượt đặt</span>
            </Link>
            <Link
              href="/staff/counter-booking"
              onClick={() => setMobileMenuOpen(false)}
              className={itemClass('/staff/counter-booking')}
            >
              <CalendarCheck2 size={15} className="text-slate-400" />
              <span>Đặt tại quầy</span>
            </Link>
          </>
        ) : (
          <>
            <Link
              href="/search"
              onClick={() => setMobileMenuOpen(false)}
              className={itemClass('/search')}
            >
              <Search size={15} className="text-slate-400" />
              <span>Tìm kiếm sân</span>
            </Link>

            {user?.roles.includes('CUSTOMER') && (
              <Link
                href="/transfers"
                onClick={() => setMobileMenuOpen(false)}
                className={itemClass('/transfers')}
              >
                <ArrowLeftRight size={15} className="text-slate-400" />
                <span>Chợ chuyển nhượng vé</span>
              </Link>
            )}

            {user && (
              <>
                <Link
                  href="/customer/bookings"
                  onClick={() => setMobileMenuOpen(false)}
                  className={itemClass('/customer/bookings')}
                >
                  <Calendar size={15} className="text-slate-400" />
                  <span>Lịch đặt của tôi</span>
                </Link>
                <Link
                  href="/customer/groups"
                  onClick={() => setMobileMenuOpen(false)}
                  className={itemClass('/customer/groups')}
                >
                  <Users size={15} className="text-slate-400" />
                  <span>Hội nhóm & Chia tiền</span>
                </Link>
                <Link
                  href="/customer/favorites"
                  onClick={() => setMobileMenuOpen(false)}
                  className={itemClass('/customer/favorites')}
                >
                  <Heart size={15} className="text-slate-400" />
                  <span>Sân yêu thích</span>
                </Link>
                <Link
                  href="/customer/profile"
                  onClick={() => setMobileMenuOpen(false)}
                  className={itemClass('/customer/profile')}
                >
                  <User size={15} className="text-slate-400" />
                  <span>Hồ sơ cá nhân</span>
                </Link>
                {user?.roles.includes('CUSTOMER') && (
                  <Link
                    href="/customer/profile/bank"
                    onClick={() => setMobileMenuOpen(false)}
                    className={itemClass('/customer/profile/bank')}
                  >
                    <Landmark size={15} className="shrink-0 text-slate-400" />
                    <span>Ngân hàng nhận tiền chuyển nhượng</span>
                  </Link>
                )}
              </>
            )}

            {user?.roles.includes('OWNER') && (
              <>
                <Link
                  href="/owner/facilities"
                  onClick={() => setMobileMenuOpen(false)}
                  className={itemClass('/owner/facilities')}
                >
                  <Building2 size={15} className="text-emerald-700" />
                  <span>Không gian Chủ sân</span>
                </Link>
                <Link
                  href="/owner/bookings"
                  onClick={() => setMobileMenuOpen(false)}
                  className={itemClass('/owner/bookings')}
                >
                  <Briefcase size={15} className="text-emerald-700" />
                  <span>Lịch đặt & Check-in khách</span>
                </Link>
                <Link
                  href="/owner/counter-booking"
                  onClick={() => setMobileMenuOpen(false)}
                  className={itemClass('/owner/counter-booking')}
                >
                  <CalendarCheck2 size={15} className="text-emerald-700" />
                  <span>Đặt tại quầy cho khách</span>
                </Link>
              </>
            )}
            {user?.roles.includes('CUSTOMER') && !user.roles.includes('OWNER') && (
              <Link
                href="/owner/application"
                onClick={() => setMobileMenuOpen(false)}
                className={itemClass('/owner/application')}
              >
                <Building2 size={15} className="text-slate-400" />
                <span>Đăng ký trở thành chủ sân</span>
              </Link>
            )}
            {user?.roles.includes('STAFF') && (
              <Link
                href="/staff/bookings"
                onClick={() => setMobileMenuOpen(false)}
                className={itemClass('/staff/bookings')}
              >
                <Briefcase size={15} className="text-slate-500" />
                <span>Kiểm tra lượt đặt</span>
              </Link>
            )}
          </>
        )}
        {user?.roles.includes('ADMIN') && (
          <Link
            href="/admin/accounts"
            onClick={() => setMobileMenuOpen(false)}
            className={itemClass('/admin/accounts')}
          >
            <ShieldCheck size={15} className="text-emerald-700" />
            <span>Bảng điều khiển quản trị viên</span>
          </Link>
        )}

        {user && (
          <div className="border-t border-slate-100 pt-1.5 mt-1.5 dark:border-slate-800">
            <button
              type="button"
              onClick={() => {
                setMobileMenuOpen(false)
                logout()
              }}
              disabled={busy}
              className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-xs font-medium text-red-600 transition-colors hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-950/40 cursor-pointer"
            >
              <LogOut size={15} />
              <span>{busy ? 'Đang đăng xuất…' : 'Đăng xuất tài khoản'}</span>
            </button>
          </div>
        )}
      </nav>
    </div>
  )
}
