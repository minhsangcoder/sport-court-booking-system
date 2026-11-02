import { card } from '@/features/transfers/transfer-styles'
import type { ReactNode } from 'react'

export { StepTitle } from '@/features/transfers/step-title'

export {
  amberNotice,
  card,
  emeraldNotice,
  fieldLabel,
  outlinePill,
  primaryPill,
} from '@/features/transfers/transfer-styles'

/** Home design language for the Owner workspace: page heads, titled panels and notices. */
export const dangerPill =
  'inline-flex h-10 items-center justify-center gap-1.5 rounded-full border border-red-200 bg-red-50 px-5 text-sm font-semibold text-red-700 transition hover:-translate-y-0.5 hover:bg-red-100 disabled:pointer-events-none disabled:opacity-50 dark:border-red-900/50 dark:bg-red-950/40 dark:text-red-300'

export const redNotice =
  'rounded-2xl border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300'

export const textLink =
  'text-sm font-semibold text-emerald-700 hover:underline dark:text-emerald-400'

export const emptyPanel =
  'rounded-3xl border border-dashed border-slate-300 bg-white/60 p-8 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900/60 dark:text-slate-400'

export function OwnerPageHead({
  title,
  description,
  actions,
}: {
  title: string
  description?: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className="home-rise flex flex-wrap items-end justify-between gap-4">
      <div className="max-w-3xl">
        <h2 className="text-2xl font-extrabold tracking-tight text-slate-950 sm:text-3xl dark:text-white">
          {title}
        </h2>
        {description && (
          <p className="mt-2 text-sm leading-relaxed text-slate-600 dark:text-slate-400">
            {description}
          </p>
        )}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2.5">{actions}</div>}
    </div>
  )
}

export function Panel({
  title,
  description,
  actions,
  children,
  className = '',
}: {
  title?: string
  description?: ReactNode
  actions?: ReactNode
  children?: ReactNode
  className?: string
}) {
  return (
    <section className={`${card} home-rise p-5 sm:p-6 ${className}`}>
      {(title || actions) && (
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            {title && (
              <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
                {title}
              </h3>
            )}
            {description && (
              <p className="mt-1 text-sm leading-relaxed text-slate-600 dark:text-slate-400">
                {description}
              </p>
            )}
          </div>
          {actions}
        </div>
      )}
      {children}
    </section>
  )
}
