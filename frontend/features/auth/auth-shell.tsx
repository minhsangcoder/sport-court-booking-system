'use client'
import type { Mode } from './auth-mode'
import { AuthShowcase } from './auth-showcase'
export function AuthShell({
  mode,
  isExpandedOwner,
  children,
}: {
  mode: Mode
  isExpandedOwner: boolean
  children: React.ReactNode
}) {
  return (
    <div
      className={`mx-auto w-full transition-all duration-300 ${
        isExpandedOwner
          ? 'max-w-4xl'
          : 'max-w-4xl rounded-xl border border-slate-200 bg-white p-4 sm:p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900'
      }`}
    >
      <div
        className={`grid w-full items-stretch gap-6 sm:gap-8 transition-all duration-300 ${
          isExpandedOwner
            ? 'rounded-xl border border-slate-200 bg-white p-4 sm:p-6 lg:p-8 shadow-sm dark:border-slate-800 dark:bg-slate-900'
            : mode === 'login' || mode === 'register'
              ? 'lg:grid-cols-12'
              : 'max-w-md mx-auto'
        }`}
      >
        <div
          className={`${
            isExpandedOwner
              ? 'w-full'
              : mode === 'login' || mode === 'register'
                ? 'lg:col-span-6 flex flex-col justify-center py-1'
                : 'w-full'
          }`}
        >
          {children}
        </div>
        {!isExpandedOwner && (mode === 'login' || mode === 'register') && <AuthShowcase />}
      </div>
    </div>
  )
}
