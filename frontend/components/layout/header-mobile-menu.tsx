'use client'
import {
  Briefcase,
  Building2,
  Calendar,
  CalendarCheck2,
  Heart,
  Landmark,
  LogOut,
  Repeat,
  Search,
  Shield,
  Sparkles,
  Trophy,
  User,
  Users,
} from 'lucide-react'
import Link from 'next/link'
import type { HeaderModel } from './use-header'
import { displayLabel } from '@/lib/display-labels'
export function HeaderMobileMenu({ model }: { model: HeaderModel }) {
  const { user, isStaffOnly, busy, setMobileMenuOpen, logout, isActive } = model
  return (
    <div className="border-b border-slate-200 bg-white/98 backdrop-blur-xl p-4 shadow-2xl md:hidden dark:border-slate-800 dark:bg-slate-900/98 max-h-[calc(100vh-64px)] overflow-y-auto animate-in slide-in-from-top duration-200">
      {/* Guest Auth Banner in Mobile Drawer */}
      {!user ? (
        <div className="mb-4 rounded-2xl border border-emerald-100 bg-gradient-to-br from-emerald-500/10 via-teal-500/5 to-transparent p-4 dark:border-emerald-900/50 dark:bg-emerald-950/20">
          <div className="flex items-center gap-3">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-tr from-emerald-600 to-teal-500 text-white shadow-md shadow-emerald-600/20">
              <Trophy size={20} />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-900 dark:text-white">
                Chào mừng đến SportHub
              </h3>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Tham gia để đặt sân, ghép trận và nhận ưu đãi
              </p>
            </div>
          </div>
          <div className="mt-3.5 grid grid-cols-2 gap-2">
            <Link
              href="/login"
              onClick={() => setMobileMenuOpen(false)}
              className="flex h-10 items-center justify-center rounded-xl border border-slate-200 bg-white text-xs font-bold text-slate-800 shadow-2xs hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 transition-colors"
            >
              Đăng nhập
            </Link>
            <Link
              href="/register"
              onClick={() => setMobileMenuOpen(false)}
              className="flex h-10 items-center justify-center rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 text-xs font-bold text-white shadow-sm hover:from-emerald-500 hover:to-teal-500 transition-all"
            >
              Đăng ký ngay
            </Link>
          </div>
        </div>
      ) : (
        /* Logged-in User Profile Header in Mobile Drawer */
        <div className="mb-4 rounded-2xl border border-slate-200 bg-slate-50/80 p-3.5 dark:border-slate-800 dark:bg-slate-800/60">
          <div className="flex items-center gap-3">
            <div className="flex size-10 items-center justify-center rounded-full bg-emerald-600 text-sm font-bold text-white shrink-0 shadow-sm">
              {user.fullName ? user.fullName.charAt(0).toUpperCase() : 'U'}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-bold text-slate-900 dark:text-white">
                {user.fullName}
              </p>
              <p className="truncate text-xs text-slate-500 dark:text-slate-400">
                {user.email || user.phone}
              </p>
            </div>
          </div>
          <div className="mt-2.5 flex flex-wrap gap-1">
            {user.roles.map((r) => (
              <span
                key={r}
                className="rounded-md bg-emerald-100/80 px-2 py-0.5 text-[10px] font-bold text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
              >
                {displayLabel(r)}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Navigation Links */}
      <nav className="space-y-1 font-semibold text-slate-700 dark:text-slate-200">
        {isStaffOnly ? (
          <>
            <Link
              href="/staff/bookings"
              onClick={() => setMobileMenuOpen(false)}
              className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                isActive('/staff/bookings')
                  ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400 font-bold'
                  : 'hover:bg-slate-100 dark:hover:bg-slate-800'
              }`}
            >
              <Briefcase size={18} className="text-emerald-600" />
              <span>Kiểm tra lượt đặt</span>
            </Link>
            <Link
              href="/staff/counter-booking"
              onClick={() => setMobileMenuOpen(false)}
              className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                isActive('/staff/counter-booking')
                  ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400 font-bold'
                  : 'hover:bg-slate-100 dark:hover:bg-slate-800'
              }`}
            >
              <CalendarCheck2 size={18} className="text-emerald-600" />
              <span>Đặt tại quầy</span>
            </Link>
          </>
        ) : (
          <>
            <Link
              href="/search"
              onClick={() => setMobileMenuOpen(false)}
              className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                isActive('/search')
                  ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400'
                  : 'hover:bg-slate-100 dark:hover:bg-slate-800'
              }`}
            >
              <Search size={18} className="text-emerald-600" />
              <span>Tìm kiếm sân</span>
            </Link>

            {user?.roles.includes('CUSTOMER') && (
              <Link
                href="/transfers"
                onClick={() => setMobileMenuOpen(false)}
                className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                  isActive('/transfers')
                    ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400'
                    : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                }`}
              >
                <Repeat size={18} className="text-emerald-600" />
                <span>Chợ chuyển nhượng vé</span>
              </Link>
            )}

            {user && (
              <>
                <Link
                  href="/customer/bookings"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                    isActive('/customer/bookings')
                      ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400'
                      : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                  }`}
                >
                  <Calendar size={18} className="text-emerald-600" />
                  <span>Lịch đặt của tôi</span>
                </Link>
                <Link
                  href="/customer/groups"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                    isActive('/customer/groups')
                      ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400'
                      : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                  }`}
                >
                  <Users size={18} className="text-emerald-600" />
                  <span>Hội nhóm & Chia tiền</span>
                </Link>
                <Link
                  href="/customer/favorites"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                    isActive('/customer/favorites')
                      ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400'
                      : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                  }`}
                >
                  <Heart size={18} className="text-emerald-600" />
                  <span>Sân yêu thích</span>
                </Link>
                <Link
                  href="/customer/profile"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                    isActive('/customer/profile')
                      ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-400'
                      : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                  }`}
                >
                  <User size={18} className="text-emerald-600" />
                  <span>Hồ sơ cá nhân</span>
                </Link>
                {user?.roles.includes('CUSTOMER') && (
                  <Link
                    href="/customer/profile/bank"
                    onClick={() => setMobileMenuOpen(false)}
                    className="flex items-center gap-3 rounded-xl px-3 py-2.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
                  >
                    <Landmark size={18} className="shrink-0 text-emerald-600" />
                    <span>Ngân hàng nhận tiền chuyển nhượng</span>
                  </Link>
                )}
              </>
            )}

            {/* Role Links */}
            {user?.roles.includes('OWNER') && (
              <>
                <Link
                  href="/owner/facilities"
                  onClick={() => setMobileMenuOpen(false)}
                  className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-emerald-700 hover:bg-emerald-50 dark:text-emerald-400 dark:hover:bg-emerald-950"
                >
                  <Building2 size={18} />
                  <span>Không gian Chủ sân</span>
                </Link>
                <Link
                  href="/owner/bookings"
                  onClick={() => setMobileMenuOpen(false)}
                  className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-emerald-700 hover:bg-emerald-50"
                >
                  <Briefcase size={18} />
                  <span>Lịch đặt & Check-in khách</span>
                </Link>
                <Link
                  href="/owner/counter-booking"
                  onClick={() => setMobileMenuOpen(false)}
                  className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-emerald-700 hover:bg-emerald-50"
                >
                  <CalendarCheck2 size={18} />
                  <span>Đặt tại quầy cho khách</span>
                </Link>
              </>
            )}
            {user?.roles.includes('CUSTOMER') && !user.roles.includes('OWNER') && (
              <Link
                href="/owner/application"
                onClick={() => setMobileMenuOpen(false)}
                className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-amber-700 hover:bg-amber-50 dark:text-amber-400 dark:hover:bg-amber-950"
              >
                <Sparkles size={18} className="text-amber-600" />
                <span>Đăng ký trở thành chủ sân</span>
              </Link>
            )}
            {user?.roles.includes('STAFF') && (
              <Link
                href="/staff/bookings"
                onClick={() => setMobileMenuOpen(false)}
                className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-blue-700 hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-blue-950"
              >
                <Briefcase size={18} />
                <span>Kiểm tra lượt đặt</span>
              </Link>
            )}
          </>
        )}
        {user?.roles.includes('ADMIN') && (
          <Link
            href="/admin/accounts"
            onClick={() => setMobileMenuOpen(false)}
            className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-amber-700 hover:bg-amber-50 dark:text-amber-400 dark:hover:bg-amber-950"
          >
            <Shield size={18} />
            <span>Bảng điều khiển quản trị viên</span>
          </Link>
        )}

        {/* Logout button in Mobile Drawer */}
        {user && (
          <div className="border-t border-slate-100 pt-2 mt-2 dark:border-slate-800">
            <button
              type="button"
              onClick={() => {
                setMobileMenuOpen(false)
                logout()
              }}
              disabled={busy}
              className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm font-semibold text-rose-600 transition-colors hover:bg-rose-50 dark:text-rose-400 dark:hover:bg-rose-950/40 cursor-pointer"
            >
              <LogOut size={18} />
              <span>{busy ? 'Đang đăng xuất…' : 'Đăng xuất tài khoản'}</span>
            </button>
          </div>
        )}
      </nav>
    </div>
  )
}
