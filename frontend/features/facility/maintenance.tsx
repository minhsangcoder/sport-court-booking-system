'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  ImpactNotice,
  readImpact,
  type ConfigurationImpact,
} from '@/features/integration/configuration-impact'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
import { useFacility } from './facility-store'
import {
  OwnerPageHead,
  amberNotice,
  card,
  dangerPill,
  emeraldNotice,
  emptyPanel,
  fieldLabel,
  primaryPill,
  redNotice,
} from './owner-ui'
import type { Court, Maintenance } from './types'
export function MaintenancePage() {
  const { facility } = useFacility()
  const courts = useApi<Court[]>(`/owner/facilities/${facility.id}/courts`)
  const [selection, setSelection] = useState('')
  const courtId = selection || courts.data?.[0]?.id
  const windows = useApi<Maintenance[]>(courtId ? `/owner/courts/${courtId}/maintenance` : null)
  const [impact, setImpact] = useState<ConfigurationImpact | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy || !courtId) return
    setBusy(true)
    setError('')
    setMessage('')
    setImpact(null)
    const form = new FormData(event.currentTarget)
    try {
      await api(`/owner/courts/${courtId}/maintenance`, {
        method: 'POST',
        body: JSON.stringify({
          startsAt: new Date(String(form.get('startsAt'))).toISOString(),
          endsAt: new Date(String(form.get('endsAt'))).toISOString(),
          reason: form.get('reason'),
        }),
      })
      windows.reload()
      setMessage('Đã lưu thời gian bảo trì.')
    } catch (err) {
      setImpact(readImpact(err))
      setError(err instanceof Error ? err.message : 'Không thể lưu bảo trì.')
    } finally {
      setBusy(false)
    }
  }
  async function cancel(id: string) {
    if (!window.confirm('Hủy thời gian bảo trì này?')) return
    setBusy(true)
    setError('')
    try {
      await api(`/owner/maintenance/${id}`, { method: 'DELETE' })
      windows.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể hủy bảo trì.')
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="space-y-6">
      <OwnerPageHead
        title="Bảo trì sân"
        description="Tạm khóa một sân trong khoảng thời gian sửa chữa để khách không đặt được."
      />
      {courts.loading && <p role="status">Đang tải sân…</p>}
      {courts.error && (
        <p role="alert" className={redNotice}>
          {courts.error}
        </p>
      )}
      {courts.data?.length === 0 && (
        <p className={emptyPanel}>Tạo sân trước khi thiết lập bảo trì.</p>
      )}
      {courtId && (
        <>
          <form
            onSubmit={save}
            className={`${card} home-rise grid gap-4 p-5 sm:grid-cols-2 sm:p-6`}
          >
            <label className={`${fieldLabel} sm:col-span-2`}>
              Sân
              <select
                aria-label="Chọn sân bảo trì"
                className="rounded-xl border bg-white px-3.5 dark:bg-slate-800"
                value={courtId}
                onChange={(e) => setSelection(e.target.value)}
                disabled={busy}
              >
                {courts.data?.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label className={fieldLabel}>
              Bắt đầu
              <Input name="startsAt" type="datetime-local" required disabled={busy} />
            </label>
            <label className={fieldLabel}>
              Kết thúc
              <Input name="endsAt" type="datetime-local" required disabled={busy} />
            </label>
            <label className={`${fieldLabel} sm:col-span-2`}>
              Lý do
              <Input name="reason" maxLength={1200} required disabled={busy} />
            </label>
            <Button
              className={`${primaryPill} justify-self-start sm:col-span-2`}
              disabled={busy}
              type="submit"
            >
              {busy ? 'Đang lưu…' : 'Tạo thời gian bảo trì'}
            </Button>
          </form>
          <p className={amberNotice}>
            Không tự hủy lượt đặt đã xác nhận. Cần xem và xử lý lượt đặt bị ảnh hưởng theo chính
            sách được chốt.
          </p>
          <ImpactNotice impact={impact} />
          {(error || windows.error) && (
            <p className={redNotice} role="alert">
              {error || windows.error}
            </p>
          )}
          {message && (
            <p className={emeraldNotice} role="status">
              {message}
            </p>
          )}
          {windows.loading && (
            <p role="status" className="mt-4">
              Đang tải bảo trì…
            </p>
          )}
          {windows.data?.length === 0 && <p className={emptyPanel}>Chưa có thời gian bảo trì.</p>}
          <div className="grid gap-3">
            {windows.data?.map((w) => (
              <article
                className={`${card} home-rise flex flex-wrap items-center justify-between gap-3 p-4 sm:p-5`}
                key={w.id}
              >
                <div>
                  <p className="font-bold text-slate-950 dark:text-white">{w.reason}</p>
                  <p className="mt-1 text-sm text-slate-500">
                    {new Date(w.startsAt).toLocaleString('vi-VN')} →{' '}
                    {new Date(w.endsAt).toLocaleString('vi-VN')}
                  </p>
                </div>
                <button className={dangerPill} onClick={() => cancel(w.id)} disabled={busy}>
                  Hủy bảo trì
                </button>
              </article>
            ))}
          </div>
        </>
      )}
    </section>
  )
}
