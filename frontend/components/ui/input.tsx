import * as React from 'react'
import { Input as InputPrimitive } from '@base-ui/react/input'
import { cn } from 'cn'

function Input({ className, type, ...props }: React.ComponentProps<'input'>) {
  return (
    <InputPrimitive
      type={type}
      data-slot="input"
      className={cn(
        'h-11 w-full min-w-0 rounded-xl border border-slate-200 bg-white px-3.5 py-1.5 text-sm text-slate-900 transition outline-none placeholder:text-slate-400 focus-visible:border-emerald-500 focus-visible:ring-3 focus-visible:ring-emerald-500/20 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-slate-100 disabled:opacity-60 aria-invalid:border-destructive aria-invalid:ring-2 aria-invalid:ring-destructive/20 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 shadow-2xs',
        className,
      )}
      {...props}
    />
  )
}

export { Input }
