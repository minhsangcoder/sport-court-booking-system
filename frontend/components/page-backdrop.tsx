import { CourtLines, DotGrid } from '@/features/home/home-art'

/** Fixed, soft colour wash + faint court lines behind every page so wide white areas are not flat. */
export function PageBackdrop() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <div className="absolute -left-40 top-16 size-[520px] rounded-full bg-emerald-200/30 blur-3xl dark:bg-emerald-500/10" />
      <DotGrid className="absolute inset-0 text-slate-400/25 [mask-image:radial-gradient(ellipse_at_top,black,transparent_65%)] dark:text-slate-600/25" />
      <CourtLines className="absolute -right-32 bottom-8 w-[780px] -rotate-12 text-emerald-900/[0.06] dark:text-white/[0.05]" />
    </div>
  )
}
