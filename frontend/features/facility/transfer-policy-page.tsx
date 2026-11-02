'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { TransferPolicy } from '@/features/transfers/types'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
import { useFacility } from './facility-store'
import {
  OwnerPageHead,
  amberNotice,
  card,
  emeraldNotice,
  fieldLabel,
  primaryPill,
  redNotice,
} from './owner-ui'
export function TransferPolicyPage() {
  const { facility } = useFacility()
  const r = useApi<TransferPolicy>(`/owner/facilities/${facility.id}/transfer-policy`)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setBusy(true)
    setError('')
    setSaved(false)
    const f = new FormData(e.currentTarget)
    try {
      await api(`/owner/facilities/${facility.id}/transfer-policy`, {
        method: 'PUT',
        body: JSON.stringify({
          enabled: f.get('enabled') === 'on',
          minLeadSeconds: Math.round(Number(f.get('minutes')) * 60),
        }),
      })
      r.reload()
      setSaved(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể lưu chính sách.')
    } finally {
      setBusy(false)
    }
  }
  if (r.loading) return <p role="status">Đang tải cấu hình…</p>
  if (!r.data)
    return (
      <p role="alert" className="text-red-700">
        {r.error}
      </p>
    )
  return (
    <>
      <section className="space-y-5">
        <OwnerPageHead
          title="Điều kiện chuyển nhượng"
          description="Áp dụng riêng cho lượt đặt tại cơ sở này. Khách chỉ được đăng và nhận lượt đặt trước khoảng thời gian tối thiểu mà bạn chọn."
        />
        {!r.data.configured && (
          <p className={amberNotice}>Cơ sở chưa cấu hình; chuyển nhượng tạm chưa được mở.</p>
        )}
        <form
          key={JSON.stringify(r.data)}
          onSubmit={submit}
          className={`${card} home-rise max-w-2xl space-y-5 p-5 sm:p-6`}
        >
          <label className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-300">
            <input
              name="enabled"
              type="checkbox"
              defaultChecked={r.data.enabled}
              disabled={busy}
              className="size-4 accent-emerald-600"
            />
            Cho phép chuyển nhượng lượt đặt đã thanh toán
          </label>
          <label className={fieldLabel}>
            Thời gian tối thiểu trước giờ chơi (phút)
            <Input
              name="minutes"
              type="number"
              min={0}
              max={129600}
              step={1}
              required
              defaultValue={r.data.minLeadSeconds / 60}
              disabled={busy}
            />
          </label>
          {error && (
            <p role="alert" className={redNotice}>
              {error}
            </p>
          )}
          {saved && (
            <p role="status" className={emeraldNotice}>
              Đã lưu cấu hình cơ sở.
            </p>
          )}
          <Button type="submit" className={primaryPill} disabled={busy}>
            Lưu điều kiện chuyển nhượng
          </Button>
        </form>
      </section>
      <p className="mt-5 max-w-2xl text-sm leading-relaxed text-slate-600 dark:text-slate-400">
        Người mua thanh toán 100% giá chuyển nhượng trực tiếp cho người bán qua QR SePay. Cơ sở
        không cấu hình hay thu phí chuyển nhượng; nếu có vướng mắc về tiền, hai bên tự giải quyết
        với nhau qua chủ sân.
      </p>
    </>
  )
}
