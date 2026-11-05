'use client'
import { useRef, useState } from 'react'
import { api, ApiError } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Button } from '@/components/ui/button'

type Policy = {
  enabled: boolean
  version: number
  selectionStepMinutes: number
  minimumMinutes: number
  maximumMinutes: number | null
}

export function FlexiblePolicy({ facilityId }: { facilityId: string }) {
  const path = `/schedules/facilities/${facilityId}/booking-policy`
  const policy = useApi<Policy>(path)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [retryPending, setRetryPending] = useState(false)
  const intent = useRef<{ key: string; body: string } | null>(null)
  async function publish() {
    if (busy || !policy.data) return
    setBusy(true)
    setError('')
    if (!intent.current)
      intent.current = {
        key: crypto.randomUUID(),
        body: JSON.stringify({
          enabled: !policy.data.enabled,
          version: policy.data.version,
          selectionStepMinutes: 30,
          minimumMinutes: 30,
          maximumMinutes: null,
        }),
      }
    try {
      await api(path, {
        method: 'PUT',
        body: intent.current.body,
        headers: { 'Idempotency-Key': intent.current.key },
      })
      intent.current = null
      setRetryPending(false)
      policy.reload()
    } catch (failure) {
      const rejected = failure instanceof ApiError && [400, 403, 409].includes(failure.status)
      if (rejected) {
        intent.current = null
        policy.reload()
      }
      setRetryPending(!rejected)
      setError(
        failure instanceof Error ? failure.message : 'Không cập nhật được hình thức đặt giờ.',
      )
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="relative overflow-hidden rounded-2xl border border-emerald-200/90 bg-gradient-to-br from-emerald-50/80 via-white to-teal-50/40 p-6 shadow-xs dark:border-emerald-900/60 dark:from-emerald-950/20 dark:via-slate-900 dark:to-slate-900">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <span className="flex size-3 items-center justify-center rounded-full bg-emerald-500/20 text-emerald-700">
            <span
              className={`size-1.5 rounded-full ${policy.data?.enabled ? 'bg-emerald-500 animate-pulse' : 'bg-slate-400'}`}
            />
          </span>
          <h3 className="text-lg font-bold text-slate-900 dark:text-white">Đặt giờ tự chọn</h3>
        </div>
        {policy.data && (
          <span
            className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-bold shadow-2xs ${
              policy.data.enabled
                ? 'border border-emerald-500/30 bg-emerald-100/80 text-emerald-800 dark:border-emerald-500/40 dark:bg-emerald-950/60 dark:text-emerald-300'
                : 'border border-slate-300 bg-slate-100 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400'
            }`}
          >
            <span
              className={`size-1.5 rounded-full ${policy.data.enabled ? 'bg-emerald-500 animate-pulse' : 'bg-slate-400'}`}
            />
            {policy.data.enabled ? 'Đang bật' : 'Chưa bật'}
          </span>
        )}
      </div>

      <p className="mt-3 text-sm leading-relaxed text-slate-600 dark:text-slate-300 max-w-3xl">
        {!policy.data
          ? 'Đang kiểm tra cấu hình đặt giờ của cơ sở.'
          : policy.data.enabled
            ? 'Đang bật: khách chọn giờ theo bước 30 phút, từ 30 phút trở lên mỗi lượt.'
            : 'Chưa bật. Hãy khai giá theo giờ cho toàn bộ lịch mở cửa của các sân trước khi bật.'}{' '}
        Hệ thống kiểm tra giá còn thiếu hoặc trùng; các lượt đặt đã chốt giữ nguyên giá.
      </p>

      {(error || policy.error) && (
        <p
          role="alert"
          className="mt-3 rounded-xl border border-red-200 bg-red-50 p-3.5 text-xs font-medium text-red-700 dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
        >
          {error || policy.error}
        </p>
      )}

      <Button
        className={`mt-4 h-11 rounded-xl px-5 font-bold transition-all ${
          policy.data?.enabled
            ? 'border border-slate-300 bg-white text-slate-700 shadow-2xs hover:bg-slate-100 hover:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200'
            : 'bg-gradient-to-r from-emerald-600 to-teal-600 text-white shadow-md shadow-emerald-600/20 hover:from-emerald-500 hover:to-teal-500 hover:scale-[1.01] active:scale-[0.99]'
        }`}
        disabled={busy || policy.loading || !policy.data}
        onClick={publish}
      >
        {busy
          ? 'Đang kiểm tra bảng giá…'
          : retryPending
            ? 'Thử lại yêu cầu trước'
            : policy.data?.enabled
              ? 'Tắt đặt giờ tự chọn'
              : 'Kiểm tra giá và bật đặt giờ tự chọn'}
      </Button>
    </section>
  )
}
