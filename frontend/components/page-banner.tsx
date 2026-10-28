import { CourtLines, PickleBall, Shuttle, TennisBall } from '@/features/home/home-art'
import type { ReactNode } from 'react'

const art = { pickleball: PickleBall, tennis: TennisBall, shuttle: Shuttle }

/** Full-bleed dark banner with the sunset court photo. `children` render under the title (e.g. a search form). */
export function PageBanner({
  eyebrow,
  title,
  description,
  actions,
  children,
  icon = 'tennis',
}: {
  eyebrow?: string
  title: string
  description?: string
  actions?: ReactNode
  children?: ReactNode
  icon?: keyof typeof art
}) {
  const Art = art[icon]
  return (
    <section className="relative isolate overflow-hidden bg-slate-950 text-white">
      <div className="absolute inset-0 -z-10" aria-hidden>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src="/images/hero-sunset-960.webp"
          alt=""
          className="size-full object-cover object-[75%_center] opacity-60"
        />
        <div className="absolute inset-0 bg-gradient-to-r from-slate-950 via-slate-950/85 to-slate-950/30" />
        <CourtLines className="absolute -right-16 -top-8 w-[460px] rotate-6 text-white/[0.07]" />
      </div>
      <Art
        className="home-float pointer-events-none absolute right-[8%] top-8 hidden w-12 drop-shadow-xl md:block"
        style={{ '--home-rot': '0deg' } as React.CSSProperties}
      />
      <div className="mx-auto max-w-6xl px-4 py-9 sm:px-6 sm:py-12">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="max-w-2xl">
            {eyebrow && (
              <p className="home-rise text-[11px] font-semibold uppercase tracking-[0.16em] text-emerald-300">
                {eyebrow}
              </p>
            )}
            <h1 className="home-rise mt-2 text-3xl font-extrabold tracking-tight [animation-delay:60ms] sm:text-4xl">
              {title}
            </h1>
            {description && (
              <p className="home-rise mt-2.5 text-sm leading-relaxed text-slate-300 [animation-delay:120ms] sm:text-base">
                {description}
              </p>
            )}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2.5">{actions}</div>}
        </div>
        {children && <div className="mt-6">{children}</div>}
      </div>
    </section>
  )
}

/** Shared button styles for banner actions. */
export const bannerPrimary =
  'inline-flex h-10 items-center gap-1.5 rounded-full bg-emerald-500 px-5 text-sm font-bold text-slate-950 shadow-[0_8px_24px_-6px_rgba(16,185,129,0.6)] transition hover:bg-emerald-400'
export const bannerGhost =
  'inline-flex h-10 items-center gap-1.5 rounded-full border border-white/30 bg-white/10 px-5 text-sm font-semibold text-white backdrop-blur-md transition hover:bg-white/20'
