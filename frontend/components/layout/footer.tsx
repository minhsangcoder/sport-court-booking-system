'use client'

import Link from 'next/link'
import { Building2, LayoutGrid, MapPin, Phone, Mail, ShieldCheck, ArrowUpRight } from 'lucide-react'

export function Footer() {
  return (
    <footer className="border-t border-slate-200 bg-slate-950 text-slate-400">
      {/* Top Banner CTA for Court Owners */}
      <div className="border-b border-slate-800/80 bg-slate-900/60 py-8">
        <div className="mx-auto flex max-w-7xl flex-col items-start justify-between gap-4 px-4 sm:px-6 sm:flex-row sm:items-center">
          <div>
            <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-emerald-400">
              <Building2 size={13} strokeWidth={1.75} />
              <span>Đối tác vận hành cơ sở</span>
            </div>
            <h3 className="mt-1 text-lg sm:text-xl font-bold tracking-tight text-white">
              Bạn sở hữu cụm sân thể thao?
            </h3>
            <p className="mt-1 text-xs sm:text-sm text-slate-400 max-w-xl">
              Số hóa quy trình quản lý lịch trống, cấu hình bảng giá theo khung giờ, đối soát thanh
              toán SePay tự động và kiểm soát vé QR tại cổng.
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-3">
            <Link
              href="/owner/application"
              className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-emerald-700 px-4 text-xs sm:text-[13px] font-semibold text-white shadow-2xs transition-colors hover:bg-emerald-600"
            >
              <span>Đăng ký mở cơ sở</span>
              <ArrowUpRight size={14} />
            </Link>
          </div>
        </div>
      </div>

      {/* Main Footer Links */}
      <div className="mx-auto max-w-7xl px-4 sm:px-6 py-10">
        <div className="grid grid-cols-1 gap-8 sm:grid-cols-2 md:grid-cols-4 lg:grid-cols-5">
          {/* Brand Info */}
          <div className="lg:col-span-2">
            <Link
              href="/"
              className="inline-flex items-center gap-2 text-base font-bold tracking-tight text-white"
            >
              <div className="flex size-7 items-center justify-center rounded-md bg-emerald-700 text-white">
                <LayoutGrid size={14} strokeWidth={2} />
              </div>
              <span>
                SportHub<span className="text-emerald-500">.</span>
              </span>
            </Link>
            <p className="mt-3 max-w-sm text-xs leading-relaxed text-slate-400">
              Nền tảng đặt sân và vận hành cơ sở thể thao trực tuyến. Đồng bộ lịch trống theo thời
              gian thực, chia tiền nhóm, chuyển nhượng P2P và xác thực vé vào sân bằng mã QR.
            </p>
            <div className="mt-4 flex items-center gap-4 text-xs text-slate-400">
              <div className="flex items-center gap-1.5">
                <ShieldCheck className="size-3.5 text-emerald-500" />
                <span>Thanh toán xác thực</span>
              </div>
              <div className="flex items-center gap-1.5">
                <MapPin className="size-3.5 text-emerald-500" />
                <span>Toàn quốc</span>
              </div>
            </div>
          </div>

          {/* Khám phá */}
          <div>
            <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-200">
              Khám phá
            </h4>
            <ul className="mt-3 space-y-2 text-xs">
              <li>
                <Link href="/search" className="transition-colors hover:text-white">
                  Tìm sân gần đây
                </Link>
              </li>
              <li>
                <Link href="/transfers" className="transition-colors hover:text-white">
                  Chợ chuyển nhượng vé
                </Link>
              </li>
              <li>
                <Link href="/customer/groups" className="transition-colors hover:text-white">
                  Hội nhóm thể thao
                </Link>
              </li>
              <li>
                <Link href="/search?q=Tennis" className="transition-colors hover:text-white">
                  Sân quần vợt tiêu chuẩn
                </Link>
              </li>
              <li>
                <Link href="/search?q=Pickleball" className="transition-colors hover:text-white">
                  Sân Pickleball
                </Link>
              </li>
            </ul>
          </div>

          {/* Dành cho Chủ sân */}
          <div>
            <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-200">
              Chủ sân & Quản lý
            </h4>
            <ul className="mt-3 space-y-2 text-xs">
              <li>
                <Link href="/owner/application" className="transition-colors hover:text-white">
                  Đăng ký hợp tác
                </Link>
              </li>
              <li>
                <Link href="/owner/facilities" className="transition-colors hover:text-white">
                  Quản trị cơ sở
                </Link>
              </li>
              <li>
                <Link href="/staff/bookings" className="transition-colors hover:text-white">
                  Nhân viên vận hành
                </Link>
              </li>
              <li>
                <Link href="/staff/counter-booking" className="transition-colors hover:text-white">
                  Đặt sân tại quầy
                </Link>
              </li>
            </ul>
          </div>

          {/* Hỗ trợ & Liên hệ */}
          <div>
            <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-200">
              Hỗ trợ khách hàng
            </h4>
            <ul className="mt-3 space-y-2 text-xs text-slate-400">
              <li className="flex items-center gap-2">
                <Phone size={13} className="text-emerald-500 shrink-0" />
                <span>1900 6868 (08:00 – 22:00)</span>
              </li>
              <li className="flex items-center gap-2">
                <Mail size={13} className="text-emerald-500 shrink-0" />
                <span>support@sporthub.vn</span>
              </li>
              <li className="pt-1 text-[11px] leading-relaxed text-slate-500">
                Hỗ trợ đối soát giao dịch và xử lý sự cố vận hành sân bãi.
              </li>
            </ul>
          </div>
        </div>

        {/* Bottom Bar */}
        <div className="mt-8 flex flex-col items-center justify-between gap-3 border-t border-slate-800/80 pt-6 text-xs text-slate-500 sm:flex-row">
          <p>© 2026 SportHub Việt Nam. Giữ toàn quyền sở hữu trí tuệ.</p>
          <div className="flex gap-5">
            <Link href="/" className="hover:text-slate-300">
              Điều khoản dịch vụ
            </Link>
            <Link href="/" className="hover:text-slate-300">
              Chính sách bảo mật
            </Link>
            <Link href="/" className="hover:text-slate-300">
              Quy chế hoạt động
            </Link>
          </div>
        </div>
      </div>
    </footer>
  )
}
