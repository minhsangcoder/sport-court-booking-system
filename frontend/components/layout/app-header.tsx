'use client'
import { Briefcase, Building2, ChevronDown, LayoutGrid, Menu, ShieldCheck, X } from 'lucide-react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import { DesktopNavigation } from './header-navigation'
import { NotificationBell } from './notification-bell'
import { useHeader } from './use-header'
const HeaderAccountMenu = dynamic(() =>
  import('./header-account-menu').then((m) => m.HeaderAccountMenu),
)
const HeaderMobileMenu = dynamic(() =>
  import('./header-mobile-menu').then((m) => m.HeaderMobileMenu),
)
export function AppHeader() {
  const model = useHeader()
  const {
    isStaffOnly,
    user,
    ready,
    pathname,
    userMenuRef,
    setUserMenuOpen,
    userMenuOpen,
    setMobileMenuOpen,
    mobileMenuOpen,
    error,
  } = model
  return (
    <header className="sticky top-0 z-40 border-b border-slate-200/90 bg-white/95 backdrop-blur-md dark:border-slate-800 dark:bg-slate-900/95">
      <div className="mx-auto flex h-14 max-w-7xl items-center justify-between gap-3 px-4 sm:px-6">
        {/* Brand Logo & Desktop Nav */}
        <div className="flex items-center gap-4 lg:gap-6 min-w-0">
          <Link
            href={isStaffOnly ? '/staff/bookings' : '/'}
            className="group flex items-center gap-2 text-slate-900 transition-opacity hover:opacity-90 dark:text-white shrink-0"
          >
            <div className="flex size-7 items-center justify-center rounded-md bg-emerald-800 text-white shadow-2xs shrink-0">
              <LayoutGrid size={14} strokeWidth={2} />
            </div>
            <span className="text-base font-bold tracking-tight whitespace-nowrap">
              SportHub<span className="text-emerald-700">.</span>
            </span>
          </Link>

          <div className="hidden md:block h-4 w-px bg-slate-200 dark:bg-slate-800 shrink-0" />

          {/* Desktop Navigation */}
          <DesktopNavigation model={model} />
        </div>

        {/* Right Section: Role Portals & User Profile */}
        <div className="flex items-center gap-2 shrink-0">
          {/* Quick Role Portal Buttons (xl desktop only to prevent nav collision) */}
          {user?.roles.includes('OWNER') && (
            <Link
              href="/owner/facilities"
              className="hidden xl:inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-xs font-medium text-slate-700 transition-colors hover:border-slate-300 hover:bg-slate-100 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 whitespace-nowrap"
            >
              <Building2 size={13} className="text-emerald-700" />
              <span>Cơ sở của tôi</span>
            </Link>
          )}

          {user?.roles.includes('CUSTOMER') &&
            !user.roles.includes('OWNER') &&
            !user.roles.includes('ADMIN') &&
            !isStaffOnly && (
              <Link
                href="/owner/application"
                className="hidden xl:inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-xs font-medium text-slate-700 transition-colors hover:border-slate-300 hover:bg-slate-100 hover:text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 whitespace-nowrap"
              >
                <Building2 size={13} className="text-slate-500" />
                <span>Mở cơ sở</span>
              </Link>
            )}

          {user?.roles.includes('STAFF') && !isStaffOnly && !user.roles.includes('OWNER') && (
            <Link
              href="/staff/bookings"
              className="hidden xl:inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-xs font-medium text-slate-700 transition-colors hover:border-slate-300 hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 whitespace-nowrap"
            >
              <Briefcase size={13} className="text-slate-600" />
              <span>Vận hành</span>
            </Link>
          )}

          {user?.roles.includes('ADMIN') && (
            <Link
              href="/admin/accounts"
              className="hidden xl:inline-flex items-center gap-1.5 rounded-md border border-slate-800 bg-slate-900 px-2.5 py-1.5 text-xs font-medium text-white transition-colors hover:bg-slate-800 whitespace-nowrap"
            >
              <ShieldCheck size={13} className="text-emerald-400" />
              <span>Quản trị</span>
            </Link>
          )}

          {ready && user && <NotificationBell />}

          {/* User Auth State */}
          {!ready ? (
            <div className="flex h-8 items-center px-2.5 text-xs text-slate-400 whitespace-nowrap">
              Đang xác thực…
            </div>
          ) : !user ? (
            <div className="flex items-center gap-1.5">
              <Link
                href="/login"
                className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors whitespace-nowrap shrink-0 ${
                  pathname === '/login'
                    ? 'bg-slate-100 text-slate-900 font-semibold dark:bg-slate-800 dark:text-white'
                    : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800'
                }`}
              >
                Đăng nhập
              </Link>
              <Link
                href="/register"
                className={`hidden sm:inline-flex items-center justify-center rounded-md px-3 py-1.5 text-xs font-semibold transition-colors whitespace-nowrap shrink-0 ${
                  pathname === '/register'
                    ? 'bg-emerald-800 text-white'
                    : 'bg-emerald-700 hover:bg-emerald-800 text-white shadow-2xs'
                }`}
              >
                Đăng ký
              </Link>
            </div>
          ) : (
            /* User Dropdown */
            <div className="relative" ref={userMenuRef}>
              <button
                type="button"
                onClick={() => setUserMenuOpen((prev) => !prev)}
                aria-expanded={userMenuOpen}
                className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-2 py-1 text-left shadow-2xs transition-colors hover:border-slate-300 hover:bg-slate-50/60 dark:border-slate-700 dark:bg-slate-800 shrink-0 cursor-pointer"
              >
                <div className="flex size-6 items-center justify-center rounded-md bg-slate-900 text-[11px] font-semibold text-white shrink-0 dark:bg-emerald-700">
                  {user.fullName ? user.fullName.charAt(0).toUpperCase() : 'U'}
                </div>
                <span className="hidden sm:inline-block max-w-[120px] truncate text-xs font-medium text-slate-800 dark:text-slate-200">
                  {user.fullName || 'Tài khoản'}
                </span>
                <ChevronDown
                  size={13}
                  className={`text-slate-400 transition-transform ${userMenuOpen ? 'rotate-180' : ''}`}
                />
              </button>

              {userMenuOpen && <HeaderAccountMenu model={model} />}
            </div>
          )}

          {/* Mobile Menu Toggle Button */}
          <button
            type="button"
            onClick={() => setMobileMenuOpen((prev) => !prev)}
            aria-label={mobileMenuOpen ? 'Đóng menu' : 'Mở menu di động'}
            className="flex size-8 items-center justify-center rounded-md border border-slate-200 text-slate-700 hover:bg-slate-100 md:hidden dark:border-slate-700 dark:text-slate-300 shrink-0 transition-colors cursor-pointer"
          >
            {mobileMenuOpen ? <X size={16} /> : <Menu size={16} />}
          </button>
        </div>
      </div>

      {/* Mobile Drawer Navigation */}
      {mobileMenuOpen && <HeaderMobileMenu model={model} />}

      {error && (
        <div
          role="alert"
          className="border-t border-red-200 bg-red-50 px-4 py-1.5 text-center text-xs font-medium text-red-700"
        >
          {error}
        </div>
      )}
    </header>
  )
}
