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
    <section className="rounded-xl border border-emerald-200 bg-emerald-50 p-5">
      <h3 className="font-bold">Đặt giờ tự chọn</h3>
      <p className="mt-2 text-sm">
        {!policy.data
          ? 'Đang kiểm tra cấu hình đặt giờ của cơ sở.'
          : policy.data.enabled
            ? 'Đang bật: khách chọn giờ theo bước 30 phút, từ 30 phút trở lên mỗi lượt.'
            : 'Chưa bật. Hãy khai giá theo giờ cho toàn bộ lịch mở cửa của các sân trước khi bật.'}{' '}
        Hệ thống kiểm tra giá còn thiếu hoặc trùng; các lượt đặt đã chốt giữ nguyên giá.
      </p>
      {(error || policy.error) && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          {error || policy.error}
        </p>
      )}
      <Button className="mt-3" disabled={busy || policy.loading || !policy.data} onClick={publish}>
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
