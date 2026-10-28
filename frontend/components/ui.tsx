'use client'
import Link from 'next/link'
import type { ReactNode, ButtonHTMLAttributes } from 'react'
import { Button as BaseButton } from './ui/button'
export function Button({
  variant,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'secondary' }) {
  return <BaseButton variant={variant ? 'outline' : 'default'} {...props} />
}
export function LinkButton({
  href,
  children,
}: {
  href: string
  children: ReactNode
  variant?: string
}) {
  return (
    <Link
      href={href}
      className="inline-flex h-9 items-center rounded-lg border border-slate-300 bg-white px-3.5 text-xs sm:text-[13px] font-semibold text-slate-700 shadow-2xs transition-colors hover:border-slate-400 hover:bg-slate-50 hover:text-slate-900"
    >
      {children}
    </Link>
  )
}
export function Panel({ children }: { children: ReactNode }) {
  return (
    <section className="mt-5 rounded-xl border border-slate-200/90 bg-white p-5 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
      {children}
    </section>
  )
}
export function PageHead({ title, description }: { title: string; description?: string }) {
  return (
    <div className="border-b border-slate-200/80 pb-4 dark:border-slate-800">
      <h1 className="text-xl sm:text-2xl font-bold tracking-tight text-slate-900 dark:text-white">
        {title}
      </h1>
      {description && <p className="mt-1.5 text-xs sm:text-sm text-slate-500">{description}</p>}
    </div>
  )
}
