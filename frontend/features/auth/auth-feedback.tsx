'use client'
import { Button } from '@/components/ui/button'
import { AlertCircle, ArrowRight, CheckCircle2 } from 'lucide-react'
import Link from 'next/link'
import type { AuthModel } from './use-auth-form'

export function AuthFeedback({ model }: { model: AuthModel }) {
  const { mode, busy, error, success, googleError, params } = model
  return (
    <>
      {/* Error Message Alert */}
      {(error || googleError) && (
        <div
          role="alert"
          className="flex items-start gap-2.5 rounded-2xl border border-rose-200 bg-rose-50 p-3.5 text-xs sm:text-sm text-rose-800 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <AlertCircle size={18} className="shrink-0 text-rose-600 mt-0.5" />
          <span>{error || googleError}</span>
        </div>
      )}

      {/* Success Message Alert */}
      {success && (
        <div
          role="status"
          className="flex items-start gap-2.5 rounded-2xl border border-emerald-200 bg-emerald-50 p-3.5 text-xs sm:text-sm text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300"
        >
          <CheckCircle2 size={18} className="shrink-0 text-emerald-600 mt-0.5" />
          <div>
            <span>{success} </span>
            <Link
              className="font-bold underline text-emerald-900 dark:text-emerald-200 ml-1"
              href={
                mode === 'verify' && /^[0-9a-f-]{36}$/.test(params.get('ownerApplicationId') ?? '')
                  ? `/login?next=${encodeURIComponent(`/owner/application/${params.get('ownerApplicationId')}`)}`
                  : '/login'
              }
            >
              Đăng nhập ngay
            </Link>
          </div>
        </div>
      )}

      {/* Primary Submit Button */}
      <Button
        className="h-12 w-full rounded-2xl bg-gradient-to-r from-emerald-600 via-emerald-500 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-bold text-sm sm:text-base shadow-lg shadow-emerald-600/25 active:scale-[0.98] hover:shadow-emerald-600/35 transition-all duration-200 flex items-center justify-center gap-2 cursor-pointer group"
        type="submit"
        disabled={busy}
      >
        {busy ? (
          <>
            <div className="size-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
            <span>Đang xử lý…</span>
          </>
        ) : (
          <span key={mode} className="inline-flex items-center gap-2 auth-fade-in">
            <span>
              {mode === 'login'
                ? 'Đăng nhập ngay'
                : mode === 'register'
                  ? 'Tạo tài khoản'
                  : mode === 'verify'
                    ? 'Xác minh tài khoản'
                    : 'Tiếp tục'}
            </span>
            <ArrowRight
              size={17}
              className="transition-transform duration-200 group-hover:translate-x-1"
            />
          </span>
        )}
      </Button>
    </>
  )
}
