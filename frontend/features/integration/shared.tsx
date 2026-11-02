'use client'
import { BannerPage } from '@/components/page-banner'
import { card } from '@/features/transfers/transfer-styles'
import type { ReactNode } from 'react'
import { useState } from 'react'
export function Frame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <BannerPage title={title} width="max-w-5xl" className="space-y-5">
      {children}
    </BannerPage>
  )
}
export function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={`${card} home-rise space-y-4 p-5 sm:p-6`}>
      <h2 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
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
        <p role="status" className="text-sm text-slate-500">
          Đang tải…
        </p>
      )}
      {error && (
        <p
          role="alert"
          className="rounded-2xl border border-red-200 bg-red-50 p-4 text-sm font-medium text-red-700 dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
        >
          {error}
        </p>
      )}
      {!loading && !error && empty && (
        <p className="rounded-3xl border border-dashed border-slate-300 bg-white/60 p-8 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900/60 dark:text-slate-400">
          Chưa có bản ghi.
        </p>
      )}
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
  'h-11 rounded-xl border border-slate-200 bg-white px-3.5 text-sm text-slate-900 shadow-2xs transition focus:border-emerald-500 focus:outline-none focus:ring-3 focus:ring-emerald-500/20'
