'use client'
import {
  AlertTriangle,
  BarChart3,
  Building2,
  CalendarCheck,
  ChevronRight,
  CreditCard,
  ExternalLink,
  FileCheck,
  Layers,
  RefreshCw,
  RotateCcw,
  Shield,
  Users,
} from 'lucide-react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'

export function AdminShell({ active, children }: { active: string; children: React.ReactNode }) {
  const pathname = usePathname()

  const navSections = [
    {
      title: 'TỔNG QUAN',
      items: [
        { key: 'reports', label: 'Báo cáo & Thống kê', href: '/admin/reports', icon: BarChart3 },
      ],
    },
    {
      title: 'THẨM ĐỊNH & KIỂM DUYỆT',
      items: [
        {
          key: 'owner-applications',
          label: 'Đăng ký Chủ sân',
          href: '/admin/owner-applications',
          icon: FileCheck,
        },
        {
          key: 'facilities',
          label: 'Kiểm duyệt Cơ sở',
          href: '/admin/facilities',
          icon: Building2,
        },
      ],
    },
    {
      title: 'GIÁM SÁT DÒNG TIỀN & VẬN HÀNH',
      items: [
        {
          key: 'bookings',
          label: 'Giám sát lượt đặt',
          href: '/admin/bookings',
          icon: CalendarCheck,
        },
        {
          key: 'payments',
          label: 'Giao dịch thanh toán',
          href: '/admin/payments',
          icon: CreditCard,
        },
        {
          key: 'reconciliation',
          label: 'Đối soát thất lạc',
          href: '/admin/bookings/reconciliation',
          icon: RefreshCw,
        },
        {
          key: 'disputes',
          label: 'Khiếu nại & Tranh chấp',
          href: '/admin/disputes',
          icon: AlertTriangle,
        },
        { key: 'refunds', label: 'Rà soát hoàn tiền', href: '/admin/refunds', icon: RotateCcw },
      ],
    },
    {
      title: 'HỆ THỐNG & TÀI KHOẢN',
      items: [
        { key: 'accounts', label: 'Tài khoản người dùng', href: '/admin/accounts', icon: Users },
        { key: 'sports', label: 'Danh mục môn thể thao', href: '/admin/sports', icon: Layers },
      ],
    },
  ]

  return (
    <div className="flex min-h-[calc(100vh-56px)] bg-slate-900 text-slate-100">
      <aside className="hidden w-60 flex-col border-r border-slate-800 bg-slate-950 p-3.5 lg:flex">
        <div className="flex items-center gap-2.5 px-2 py-2.5 border-b border-slate-800/80">
          <div className="flex size-7 items-center justify-center rounded-lg bg-emerald-700 text-white shadow-2xs">
            <Shield size={14} strokeWidth={1.75} />
          </div>
          <div>
            <span className="font-bold text-xs text-white tracking-tight block">
              Quản trị SportHub
            </span>
            <span className="block text-[10px] font-semibold uppercase tracking-wider text-emerald-400">
              Bảng điều khiển
            </span>
          </div>
        </div>

        <nav className="flex-1 overflow-y-auto py-4 space-y-4">
          {navSections.map((sec) => (
            <div key={sec.title}>
              <p className="px-2 text-[10px] font-semibold uppercase tracking-wider text-slate-500 mb-1">
                {sec.title}
              </p>
              <div className="space-y-0.5">
                {sec.items.map((item) => {
                  const isCurrent = active === item.key || pathname.startsWith(item.href)
                  const Icon = item.icon
                  return (
                    <Link
                      key={item.key}
                      href={item.href}
                      className={`flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-xs font-medium transition-colors ${
                        isCurrent
                          ? 'bg-emerald-700 text-white font-semibold shadow-2xs'
                          : 'text-slate-400 hover:bg-slate-900 hover:text-white'
                      }`}
                    >
                      <Icon size={14} strokeWidth={1.75} />
                      <span>{item.label}</span>
                    </Link>
                  )
                })}
              </div>
            </div>
          ))}
        </nav>

        <div className="border-t border-slate-800 pt-3">
          <Link
            href="/"
            className="flex items-center justify-between rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-xs font-medium text-slate-300 hover:bg-slate-800 transition-colors"
          >
            <span>Về cổng khách hàng</span>
            <ExternalLink size={13} strokeWidth={1.75} className="text-slate-400" />
          </Link>
        </div>
      </aside>

      <div className="flex flex-1 flex-col min-w-0 bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
        <header className="sticky top-0 z-10 flex h-12 items-center justify-between border-b border-slate-200/80 bg-white/95 px-6 backdrop-blur-md dark:border-slate-800 dark:bg-slate-900/95">
          <div className="flex items-center gap-1.5 text-xs font-medium text-slate-500">
            <span>Bảng quản trị</span>
            <ChevronRight size={12} className="text-slate-400" />
            <span className="font-semibold text-slate-900 dark:text-white uppercase tracking-wider">
              {active}
            </span>
          </div>

          <div className="flex items-center gap-3">
            <span className="rounded-md border border-slate-200 bg-slate-100 px-2.5 py-0.5 text-[11px] font-semibold text-slate-700 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-300">
              Quyền Quản trị viên
            </span>
          </div>
        </header>

        <div className="border-b border-slate-200 bg-white p-2 lg:hidden dark:border-slate-800 dark:bg-slate-900 overflow-x-auto flex gap-1">
          {navSections
            .flatMap((s) => s.items)
            .map((item) => (
              <Link
                key={item.key}
                href={item.href}
                className={`whitespace-nowrap rounded-md px-2.5 py-1.5 text-xs font-semibold ${
                  active === item.key
                    ? 'bg-emerald-700 text-white'
                    : 'text-slate-600 hover:bg-slate-100'
                }`}
              >
                {item.label}
              </Link>
            ))}
        </div>

        <main className="flex-1 p-4 sm:p-6 lg:p-7">{children}</main>
      </div>
    </div>
  )
}
