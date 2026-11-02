/** Numbered heading, the same badge as the Home "3 bước" section. */
export function StepTitle({
  step,
  title,
  description,
}: {
  step: number
  title: string
  description?: string
}) {
  return (
    <div className="flex items-start gap-3">
      <span className="flex size-8 shrink-0 items-center justify-center rounded-xl bg-emerald-700 text-sm font-bold text-white shadow-md">
        {step}
      </span>
      <div className="min-w-0">
        <h3 className="text-base font-extrabold tracking-tight text-slate-950 dark:text-white">
          {title}
        </h3>
        {description && (
          <p className="mt-0.5 text-sm text-slate-600 dark:text-slate-400">{description}</p>
        )}
      </div>
    </div>
  )
}
