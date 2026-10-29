'use client'
import { ArrowRight } from 'lucide-react'
import Link from 'next/link'
import { rememberGoogleNext } from './google-auth-state'
import type { AuthModel } from './use-auth-form'
function GoogleLogo({ className = 'size-5' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" aria-hidden="true">
      <path
        fill="#4285F4"
        d="M23.745 12.27c0-.7-.06-1.4-.19-2.07H12v4.51h6.6c-.29 1.52-1.14 2.8-2.4 3.65v3.05h3.88c2.27-2.09 3.66-5.17 3.66-9.14z"
      />
      <path
        fill="#34A853"
        d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.88-3.05c-1.08.72-2.45 1.16-4.05 1.16-3.12 0-5.77-2.1-6.72-4.93H1.24v3.15C3.26 21.36 7.33 24 12 24z"
      />
      <path
        fill="#FBBC05"
        d="M5.28 14.27c-.25-.72-.38-1.49-.38-2.27s.13-1.55.38-2.27V6.58H1.24C.45 8.15 0 9.92 0 12s.45 3.85 1.24 5.42l4.04-3.15z"
      />
      <path
        fill="#EA4335"
        d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42C17.95 1.19 15.24 0 12 0 7.33 0 3.26 2.64 1.24 6.58l4.04 3.15c.95-2.83 3.6-4.98 6.72-4.98z"
      />
    </svg>
  )
}

export function AuthOptions({ model }: { model: AuthModel }) {
  const { mode, switchMode, busy, applyAsOwner, params } = model
  return (
    <>
      {/* Google OAuth & Divider */}
      {(mode === 'login' || (mode === 'register' && !applyAsOwner)) && (
        <div className="space-y-4 pt-1 auth-fade-in">
          <div className="relative flex items-center justify-center">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-slate-200 dark:border-slate-800" />
            </div>
            <span className="relative bg-white px-3 text-xs font-medium uppercase tracking-wider text-slate-400 dark:bg-slate-900">
              hoặc tiếp tục với
            </span>
          </div>

          <button
            type="button"
            onClick={() => {
              if (busy) {
                return
              }
              rememberGoogleNext(params.get('next'))
              window.location.assign(
                new URL('/api/v1/auth/oauth2/authorization/google', window.location.origin).href,
              )
            }}
            aria-disabled={busy}
            tabIndex={busy ? -1 : undefined}
            className={`flex h-12 w-full items-center justify-center gap-3 rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm font-semibold text-slate-700 dark:text-slate-200 shadow-xs transition-all hover:bg-slate-50 dark:hover:bg-slate-700/80 active:scale-[0.99] ${
              busy ? 'pointer-events-none opacity-50' : ''
            }`}
          >
            <GoogleLogo className="size-5" />
            <span>{mode === 'register' ? 'Đăng ký với Google' : 'Đăng nhập với Google'}</span>
          </button>
        </div>
      )}

      {/* Bottom Nav Links */}
      <div className="flex items-center justify-center pt-2 text-xs sm:text-sm font-semibold">
        {mode === 'login' || mode === 'register' ? (
          <button
            type="button"
            onClick={() => switchMode(mode === 'login' ? 'register' : 'login')}
            className="text-emerald-600 hover:text-emerald-700 dark:text-emerald-400 hover:underline cursor-pointer inline-flex items-center gap-1.5 transition-colors group"
          >
            <span>
              {mode === 'login'
                ? 'Chưa có tài khoản? Đăng ký ngay'
                : 'Đã có tài khoản? Quay lại đăng nhập'}
            </span>
            <ArrowRight
              size={14}
              className="transition-transform duration-200 group-hover:translate-x-1"
            />
          </button>
        ) : (
          <Link
            href="/login"
            className="text-emerald-600 hover:text-emerald-700 dark:text-emerald-400 hover:underline inline-flex items-center gap-1"
          >
            Quay lại đăng nhập
          </Link>
        )}
      </div>
    </>
  )
}
