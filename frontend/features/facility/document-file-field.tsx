import type { InputHTMLAttributes } from 'react'
export function DocumentFileField({
  label,
  image = false,
  error,
  ...props
}: { label: string; image?: boolean; error?: string } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="grid min-w-0 gap-3 text-sm font-medium">
      {label}
      <span className="text-xs font-normal text-slate-500">
        {image ? 'JPEG hoặc PNG' : 'PDF, JPEG hoặc PNG'} · tối đa 10 MB
      </span>
      <input
        className="block w-full min-w-0 rounded-2xl border border-dashed border-slate-300 bg-slate-50/50 p-3 text-sm file:mr-3 file:cursor-pointer file:rounded-full file:border-0 file:bg-emerald-50 file:px-4 file:py-1.5 file:text-xs file:font-semibold file:text-emerald-800 dark:border-slate-700 dark:bg-slate-800/40"
        type="file"
        accept={image ? 'image/jpeg,image/png' : 'application/pdf,image/jpeg,image/png'}
        required
        aria-invalid={!!error}
        {...props}
      />
      {error && <span className="text-xs text-red-700">{error}</span>}
    </label>
  )
}

export { validateSignupFiles } from './signup-files'
