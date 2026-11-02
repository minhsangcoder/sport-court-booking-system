'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { type ScheduleException } from '@/features/schedule/types'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'

export function Exceptions({ facilityId, courtId }: { facilityId: string; courtId: string }) {
  const resource = useApi<ScheduleException[]>(`/schedules/facilities/${facilityId}/exceptions`)
  const [type, setType] = useState('CLOSED')
  const [editing, setEditing] = useState<ScheduleException | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function save(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    const form = e.currentTarget
    const v = new FormData(form)
    try {
      await api(
        `/schedules/facilities/${facilityId}/exceptions${editing ? '/' + editing.id : ''}`,
        {
          method: editing ? 'PUT' : 'POST',
          body: JSON.stringify({
            courtId: courtId || null,
            date: v.get('date'),
            type,
            opensAt: type === 'CLOSED' ? null : v.get('opensAt'),
            closesAt: type === 'CLOSED' ? null : v.get('closesAt'),
            reason: v.get('reason'),
          }),
        },
      )
      resource.reload()
      form.reset()
      setEditing(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể lưu ngoại lệ.')
    } finally {
      setBusy(false)
    }
  }
  async function remove(id: string) {
    if (!window.confirm('Bỏ ngoại lệ này?')) return
    setBusy(true)
    setError('')
    try {
      await api(`/schedules/facilities/${facilityId}/exceptions/${id}`, { method: 'DELETE' })
      resource.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể bỏ ngoại lệ.')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div>
      <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
        Ngày nghỉ và giờ đặc biệt
      </h3>
      <p className="mt-1 text-xs text-slate-500">
        Thiết lập ngày nghỉ hoặc giờ mở cửa riêng cho một ngày cụ thể (nếu khác với lịch cố định cả
        tuần ở trên).
      </p>
      <form key={editing?.id ?? 'new'} className="mt-4 grid gap-4 sm:grid-cols-2" onSubmit={save}>
        <label className="grid gap-2 text-sm">
          Ngày
          <Input type="date" name="date" defaultValue={editing?.date} required disabled={busy} />
        </label>
        <label className="grid gap-2 text-sm">
          Loại ngoại lệ
          <select
            className="rounded-xl border bg-white px-3.5 dark:bg-slate-800"
            value={type}
            onChange={(e) => setType(e.target.value)}
            disabled={busy}
          >
            <option value="CLOSED">Đóng cửa</option>
            <option value="SPECIAL_HOURS">Giờ đặc biệt</option>
          </select>
        </label>
        {type === 'SPECIAL_HOURS' && (
          <>
            <label className="grid gap-2 text-sm">
              Mở cửa
              <Input
                type="time"
                name="opensAt"
                defaultValue={editing?.opensAt ?? undefined}
                required
                disabled={busy}
              />
            </label>
            <label className="grid gap-2 text-sm">
              Đóng cửa
              <Input
                type="time"
                name="closesAt"
                defaultValue={editing?.closesAt ?? undefined}
                required
                disabled={busy}
              />
            </label>
          </>
        )}
        <label className="grid gap-2 text-sm sm:col-span-2">
          Lý do
          <Input
            name="reason"
            defaultValue={editing?.reason}
            required
            maxLength={500}
            disabled={busy}
          />
        </label>
        <Button type="submit" disabled={busy}>
          {busy ? 'Đang lưu…' : editing ? 'Lưu ngoại lệ' : 'Thêm ngoại lệ'}
        </Button>
        {editing && (
          <Button type="button" variant="outline" onClick={() => setEditing(null)}>
            Hủy chỉnh sửa ngoại lệ
          </Button>
        )}
      </form>
      {(resource.error || error) && (
        <p role="alert" className="mt-4 text-red-700">
          {error || resource.error}
        </p>
      )}
      {resource.loading && (
        <p role="status" className="mt-4">
          Đang tải ngoại lệ…
        </p>
      )}
      <div className="mt-5 space-y-3">
        {resource.data
          ?.filter((e) => (e.courtId || '') === courtId)
          .map((e) => (
            <article
              className="flex flex-wrap justify-between gap-3 rounded-2xl border border-slate-200/80 bg-slate-50/80 p-4 dark:border-slate-800 dark:bg-slate-800/50"
              key={e.id}
            >
              <div className="text-sm">
                <p className="font-semibold">
                  {e.date} · {e.type === 'CLOSED' ? 'Đóng cửa' : `${e.opensAt} – ${e.closesAt}`}
                </p>
                <p className="mt-1 text-slate-500">{e.reason}</p>
              </div>
              <Button
                type="button"
                variant="outline"
                disabled={busy}
                onClick={() => {
                  setEditing(e)
                  setType(e.type)
                }}
              >
                Chỉnh sửa ngoại lệ
              </Button>
              <button className="text-sm text-red-700" disabled={busy} onClick={() => remove(e.id)}>
                Bỏ ngoại lệ
              </button>
            </article>
          ))}
        {resource.data?.filter((e) => (e.courtId || '') === courtId).length === 0 && (
          <p className="text-sm text-slate-500">Chưa có ngoại lệ ở phạm vi này.</p>
        )}
      </div>
    </div>
  )
}
