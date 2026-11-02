'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ApiError, api } from '@/lib/api'
import { type CourtType } from '@/lib/business-api'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { useFacility } from './facility-store'
import { ArrowRight, Plus } from 'lucide-react'
import { OwnerPageHead, card, emptyPanel, fieldLabel, primaryPill, redNotice } from './owner-ui'
import type { Category, Court } from './types'
export function CourtsPage() {
  const { facility, basePath } = useFacility()
  const { data, error, loading, reload } = useApi<Court[]>(
    `/owner/facilities/${facility.id}/courts`,
  )
  return (
    <section>
      <OwnerPageHead
        title="Danh sách sân"
        description="Mỗi sân có mã riêng; chỉ sân đang bật mới nhận lượt đặt."
        actions={
          <Link className={primaryPill} href={`${basePath}/courts/new`}>
            <Plus className="size-4" />
            Thêm sân
          </Link>
        }
      />
      {loading && (
        <p role="status" className="mt-6">
          Đang tải sân…
        </p>
      )}
      {error && (
        <p role="alert" className={`${redNotice} mt-6`}>
          {error}{' '}
          <button className="ml-2 font-semibold underline" onClick={reload}>
            Thử lại
          </button>
        </p>
      )}
      {data?.length === 0 && (
        <p className={`${emptyPanel} mt-6`}>Chưa có sân. Thêm sân đầu tiên cho cơ sở này.</p>
      )}
      <div className="mt-6 grid gap-4 md:grid-cols-2">
        {data?.map((c) => (
          <article
            key={c.id}
            className={`${card} home-rise flex flex-col p-5 transition hover:shadow-2xl`}
          >
            <div className="flex items-start justify-between gap-3">
              <h3 className="text-base font-bold text-slate-950 dark:text-white">{c.name}</h3>
              <span
                className={`shrink-0 rounded-full border px-3 py-1 text-xs font-semibold ${
                  c.enabled
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200'
                    : 'border-slate-200 bg-slate-100 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300'
                }`}
              >
                {c.enabled ? 'Đang bật' : 'Đã tắt'}
              </span>
            </div>
            <p className="mt-1.5 text-xs text-slate-500 dark:text-slate-400">
              Mã sân: <span className="font-mono">{c.code}</span>
            </p>
            {c.description && (
              <p className="mt-3 text-sm text-slate-600 dark:text-slate-300">{c.description}</p>
            )}
            <Link
              className="group mt-auto inline-flex items-center gap-1.5 pt-4 text-sm font-semibold text-emerald-700 dark:text-emerald-400"
              href={`${basePath}/courts/${c.id}/edit`}
            >
              Chỉnh sửa sân
              <ArrowRight className="size-4 transition group-hover:translate-x-0.5" />
            </Link>
          </article>
        ))}
      </div>
    </section>
  )
}
export function CourtEditor({ courtId }: { courtId?: string }) {
  const court = useApi<Court>(courtId ? `/owner/courts/${courtId}` : null)
  if (court.loading) return <p role="status">Đang tải sân…</p>
  if (court.error)
    return (
      <p role="alert" className="text-red-700">
        {court.error}
      </p>
    )
  return <CourtForm court={court.data ?? undefined} />
}
function CourtForm({ court }: { court?: Court }) {
  const { facility, basePath } = useFacility()
  const router = useRouter()
  const categories = useApi<Category[]>('/sport-categories')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const types = useApi<CourtType[]>(`/owner/facilities/${facility.id}/court-types`)
  const [sportCategoryId, setSportCategoryId] = useState(court?.sportCategoryId ?? '')
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    setFields({})
    const form = new FormData(event.currentTarget)
    try {
      await api<Court>(
        court ? `/owner/courts/${court.id}` : `/owner/facilities/${facility.id}/courts`,
        {
          method: court ? 'PUT' : 'POST',
          body: JSON.stringify({
            code: String(form.get('code')).trim(),
            name: String(form.get('name')).trim(),
            sportCategoryId: form.get('sportCategoryId'),
            description: form.get('description'),
            courtTypeId: form.get('courtTypeId') || null,
            enabled: form.get('enabled') === 'on',
          }),
        },
      )
      router.push(`${basePath}/courts`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể lưu sân.')
      if (err instanceof ApiError)
        setFields(Object.fromEntries(err.fieldErrors.map((f) => [f.field, f.message])))
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="space-y-6">
      <OwnerPageHead title={court ? 'Chỉnh sửa sân' : 'Thêm sân mới'} />
      <form className={`${card} home-rise grid max-w-2xl gap-5 p-5 sm:p-6`} onSubmit={save}>
        <label className={fieldLabel}>
          Mã sân
          <Input name="code" defaultValue={court?.code} required maxLength={50} disabled={busy} />
          {fields.code && <span className="text-red-700">{fields.code}</span>}
        </label>
        <label className={fieldLabel}>
          Tên sân
          <Input name="name" defaultValue={court?.name} required maxLength={180} disabled={busy} />
          {fields.name && <span className="text-red-700">{fields.name}</span>}
        </label>
        <label className={fieldLabel}>
          Môn thể thao
          <select
            name="sportCategoryId"
            className="rounded-xl border bg-white px-3.5 dark:bg-slate-800"
            value={sportCategoryId}
            onChange={(event) => setSportCategoryId(event.target.value)}
            required
            disabled={busy || categories.loading}
          >
            <option value="">Chọn môn thể thao</option>
            {categories.data
              ?.filter((c) => c.active || c.id === court?.sportCategoryId)
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                  {!c.active ? ' (ngừng tạo mới)' : ''}
                </option>
              ))}
          </select>
        </label>
        {categories.loading && <p role="status">Đang tải danh mục…</p>}
        {categories.error && (
          <p role="alert" className="text-red-700">
            {categories.error}
          </p>
        )}
        {categories.data?.length === 0 && (
          <p>Danh mục môn thể thao đang trống; quản trị viên cần tạo danh mục trước.</p>
        )}
        <label className={fieldLabel}>
          Loại sân
          <select
            name="courtTypeId"
            defaultValue={court?.courtTypeId ?? ''}
            className="rounded-xl border bg-white px-3.5 dark:bg-slate-800"
          >
            <option value="">Chưa phân loại</option>
            {types.data
              ?.filter((t) => t.active || t.id === court?.courtTypeId)
              .map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
          </select>
        </label>
        <label className={fieldLabel}>
          Mô tả
          <textarea
            name="description"
            className="min-h-24 rounded-xl border bg-white p-3.5 dark:bg-slate-800"
            defaultValue={court?.description}
            maxLength={5000}
            disabled={busy}
          />
        </label>
        <label className="flex items-center gap-3 text-sm font-medium text-slate-700 dark:text-slate-300">
          <input
            name="enabled"
            type="checkbox"
            defaultChecked={court?.enabled ?? true}
            disabled={busy}
            className="size-4 accent-emerald-600"
          />
          Bật sân để vận hành
        </label>
        {error && (
          <p role="alert" className="text-red-700">
            {error}
          </p>
        )}
        <Button type="submit" disabled={busy || categories.loading} className={primaryPill}>
          {busy ? 'Đang lưu…' : 'Lưu sân'}
        </Button>
      </form>
    </section>
  )
}
