'use client'
import type { ReactNode } from 'react'
import { useState } from 'react'
export function Frame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="mx-auto max-w-5xl space-y-5 px-4 sm:px-6 py-8">
      <div className="border-b border-slate-200/80 pb-3.5 dark:border-slate-800">
        <h1 className="text-xl sm:text-2xl font-bold tracking-tight text-slate-900 dark:text-white">
          {title}
        </h1>
      </div>
      {children}
    </div>
  )
}
export function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-3.5 rounded-xl border border-slate-200/90 bg-white p-5 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
      <h2 className="text-base font-semibold tracking-tight text-slate-900 dark:text-white">
        {title}
      </h2>
      {children}
    </section>
  )
}
export function Status({
  loading,
  error,
  empty = false,
}: {
  loading?: boolean
  error?: string
  empty?: boolean
}) {
  return (
    <>
      {loading && (
        <p role="status" className="text-xs text-slate-500">
          Đang tải…
        </p>
      )}
      {error && (
        <p
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 px-3.5 py-2.5 text-xs font-medium text-red-700"
        >
          {error}
        </p>
      )}
      {!loading && !error && empty && <p className="text-xs text-slate-500">Chưa có bản ghi.</p>}
    </>
  )
}
export function useMutation() {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [message, setMessage] = useState('')
  async function run<T>(work: () => Promise<T>, done?: (result: T) => void) {
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const result = await work()
      done?.(result)
      return result
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Không thể thực hiện yêu cầu.')
    } finally {
      setBusy(false)
    }
  }
  return { busy, error, message, setMessage, run }
}
export const selectStyle =
  'h-9 rounded-lg border border-slate-300 bg-white px-3 text-xs sm:text-[13px] text-slate-900 shadow-2xs focus:border-emerald-700 focus:outline-none'
