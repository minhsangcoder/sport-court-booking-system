'use client'
import type { useProfile } from '@/features/profile/use-profile'
import { displayLabel } from '@/lib/display-labels'
import {
  AlertCircle,
  ArrowRight,
  Building2,
  Check,
  CheckCircle2,
  ChevronRight,
  Copy,
  Key,
  Mail,
  Phone,
} from 'lucide-react'
import Link from 'next/link'
export function ProfileSecurity({
  model,
}: {
  model: Pick<ReturnType<typeof useProfile>, 'user' | 'copyUserId' | 'copiedId'>
}) {
  const { user, copyUserId, copiedId } = model
  return (
    <div className="space-y-6">
      {/* Account Identifier Card */}
      <div className="p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
        <h4 className="text-sm font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">
          Định danh tài khoản
        </h4>

        <div className="mt-4 space-y-3">
          <div className="rounded-2xl bg-slate-50 p-3 dark:bg-slate-800/60">
            <span className="text-[11px] font-medium text-slate-400 block">MÃ NGƯỜI DÙNG</span>
            <div className="flex items-center justify-between gap-2 mt-1">
              <code className="text-xs font-mono font-semibold text-slate-700 dark:text-slate-300 truncate">
                {user?.id || '—'}
              </code>
              <button
                type="button"
                onClick={copyUserId}
                title="Sao chép mã người dùng"
                className="rounded-lg p-1.5 text-slate-400 hover:bg-white hover:text-emerald-600 dark:hover:bg-slate-700 shadow-sm transition-all"
              >
                {copiedId ? <Check size={14} className="text-emerald-600" /> : <Copy size={14} />}
              </button>
            </div>
          </div>

          <div className="rounded-2xl bg-slate-50 p-3 dark:bg-slate-800/60">
            <span className="text-[11px] font-medium text-slate-400 block">
              TRẠNG THÁI TÀI KHOẢN
            </span>
            <div className="flex items-center gap-2 mt-1">
              <span className="size-2 rounded-full bg-emerald-500 animate-pulse" />
              <span className="text-xs font-bold text-emerald-700 dark:text-emerald-400 uppercase">
                {displayLabel(user?.status || 'ACTIVE')}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Contact & Verification Card */}
      <div className="p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
        <div className="flex items-center justify-between">
          <h4 className="text-sm font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">
            Thông tin liên hệ
          </h4>
          <Link
            href="/customer/profile/contact"
            className="text-xs font-semibold text-emerald-600 hover:underline flex items-center gap-1"
          >
            Thay đổi
            <ChevronRight size={13} />
          </Link>
        </div>

        <div className="mt-4 space-y-3">
          <div className="flex items-start gap-3 rounded-2xl border border-slate-100 p-3.5 dark:border-slate-800">
            <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600 dark:bg-emerald-950/60">
              <Mail size={16} />
            </div>
            <div className="min-w-0 flex-1">
              <span className="text-[11px] font-medium text-slate-400 block">
                Thư điện tử ĐĂNG NHẬP
              </span>
              <p className="text-xs font-semibold text-slate-800 dark:text-slate-200 truncate mt-0.5">
                {user?.email || 'Chưa cung cấp'}
              </p>
              <div className="flex items-center gap-1 mt-1 text-[11px]">
                {user?.emailVerified ? (
                  <span className="inline-flex items-center gap-1 text-emerald-600 font-medium">
                    <CheckCircle2 size={12} /> Đã xác minh
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 text-amber-600 font-medium">
                    <AlertCircle size={12} /> Chưa xác minh
                  </span>
                )}
              </div>
            </div>
          </div>

          <div className="flex items-start gap-3 rounded-2xl border border-slate-100 p-3.5 dark:border-slate-800">
            <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-blue-50 text-blue-600 dark:bg-blue-950/60">
              <Phone size={16} />
            </div>
            <div className="min-w-0 flex-1">
              <span className="text-[11px] font-medium text-slate-400 block">SỐ ĐIỆN THOẠI</span>
              <p className="text-xs font-semibold text-slate-800 dark:text-slate-200 truncate mt-0.5">
                {user?.phone || 'Chưa liên kết số điện thoại'}
              </p>
              <div className="flex items-center gap-1 mt-1 text-[11px]">
                {user?.phoneVerified ? (
                  <span className="inline-flex items-center gap-1 text-emerald-600 font-medium">
                    <CheckCircle2 size={12} /> Đã xác minh
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 text-slate-400 font-medium">
                    Chưa xác minh SMS
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>

        <Link
          href="/customer/profile/contact"
          className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 bg-slate-50 py-2.5 text-xs font-semibold text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 transition-colors"
        >
          <Key size={13} />
          Quản lý thư điện tử & Số điện thoại OTP
        </Link>
      </div>

      {/* Owner CTA Banner (If not owner) */}
      {!user?.roles?.includes('OWNER') && (
        <div className="relative overflow-hidden rounded-2xl bg-gradient-to-br from-amber-500 to-orange-600 p-6 text-white shadow-lg shadow-orange-600/15">
          <div className="absolute -right-6 -bottom-6 size-28 rounded-full bg-white/10 blur-xl" />
          <Building2 size={32} className="text-amber-200" />
          <h4 className="mt-3 text-base font-bold">Bạn sở hữu sân thể thao?</h4>
          <p className="mt-1 text-xs text-amber-100 leading-relaxed">
            Đăng ký trở thành Đối tác Chủ sân để tiếp cận hàng nghìn người chơi thể thao tại Hà Nội
            ngay hôm nay.
          </p>
          <Link
            href="/register?mode=owner"
            className="mt-4 inline-flex items-center gap-1.5 rounded-xl bg-white px-4 py-2 text-xs font-bold text-amber-800 shadow-sm transition-all hover:bg-amber-50 active:scale-95"
          >
            Đăng ký làm Chủ sân
            <ArrowRight size={13} />
          </Link>
        </div>
      )}
    </div>
  )
}
