'use client'
import { Button } from '@/components/ui/button'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
import { DocumentFileField } from './document-file-field'
import { useFacility } from './facility-store'
import {
  OwnerPageHead,
  amberNotice,
  card,
  dangerPill,
  emptyPanel,
  outlinePill,
  primaryPill,
  redNotice,
  textLink,
} from './owner-ui'
export type ReviewDocument = {
  id: string
  name: string
  url: string
  contentType: string
  sizeBytes: number
  archived: boolean
}
export function FacilityDocumentPage() {
  const { facility } = useFacility()
  const r = useApi<ReviewDocument[]>(`/owner/facilities/${facility.id}/documents`)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [remove, setRemove] = useState<string | null>(null)
  const editable = !['PENDING_APPROVAL', 'REJECTED'].includes(facility.status)
  async function upload(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy) return
    const form = e.currentTarget
    setBusy(true)
    setError('')
    try {
      await api(`/owner/facilities/${facility.id}/documents`, {
        method: 'POST',
        body: new FormData(form),
      })
      form.reset()
      r.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể tải tài liệu.')
    } finally {
      setBusy(false)
    }
  }
  async function deleteFile(id: string) {
    setBusy(true)
    setError('')
    try {
      await api(`/owner/facilities/${facility.id}/documents/${id}`, { method: 'DELETE' })
      setRemove(null)
      r.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể gỡ tài liệu.')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="space-y-5">
      <OwnerPageHead
        title="Tài liệu thẩm định"
        description="Tải giấy tờ địa điểm, hợp đồng thuê hoặc sử dụng mặt bằng. Chỉ chủ cơ sở sở hữu và quản trị viên thẩm định được xem tài liệu này."
      />
      {editable && (
        <form
          onSubmit={upload}
          className={`${card} home-rise flex flex-wrap items-end gap-4 p-5 sm:p-6`}
        >
          <DocumentFileField label="Tài liệu thẩm định" name="file" disabled={busy} />
          <Button type="submit" disabled={busy} className={primaryPill}>
            {busy ? 'Đang xử lý…' : 'Tải tài liệu'}
          </Button>
        </form>
      )}
      {!editable && (
        <p className={amberNotice}>
          Tài liệu của hồ sơ đã gửi được giữ nguyên. Khi cần bổ sung, quản trị viên sẽ mở lại hồ sơ.
        </p>
      )}
      {error && (
        <p role="alert" className={redNotice}>
          {error}
        </p>
      )}
      {r.loading && (
        <p role="status" className="text-sm text-slate-500">
          Đang tải tài liệu…
        </p>
      )}
      {r.error && (
        <p role="alert" className={redNotice}>
          {r.error}
          <button className="ml-3 underline" onClick={r.reload}>
            Thử lại
          </button>
        </p>
      )}
      {r.data?.length === 0 && <p className={emptyPanel}>Chưa có tài liệu đính kèm.</p>}
      <div className="space-y-4">
        {r.data?.map((doc) => (
          <article className={`${card} home-rise p-5`} key={doc.id}>
            <div className="flex flex-wrap justify-between gap-4">
              <div>
                <a href={doc.url} target="_blank" rel="noopener noreferrer" className={textLink}>
                  {doc.name}
                </a>
                <p className="mt-2 text-xs text-slate-500">
                  {doc.contentType} · {Math.ceil(doc.sizeBytes / 1024)} KB
                </p>
              </div>
              {editable && (
                <Button
                  variant="outline"
                  className={outlinePill}
                  onClick={() => setRemove(doc.id)}
                  disabled={busy}
                >
                  Gỡ tài liệu
                </Button>
              )}
            </div>
            {remove === doc.id && (
              <div
                role="dialog"
                aria-label="Gỡ tài liệu đính kèm"
                className={`${amberNotice} mt-4`}
              >
                <p className="text-sm">Gỡ {doc.name} khỏi hồ sơ đang chuẩn bị?</p>
                <div className="mt-4 flex gap-3">
                  <Button className={dangerPill} onClick={() => deleteFile(doc.id)} disabled={busy}>
                    Xác nhận gỡ
                  </Button>
                  <Button
                    variant="outline"
                    className={outlinePill}
                    onClick={() => setRemove(null)}
                    disabled={busy}
                  >
                    Giữ lại
                  </Button>
                </div>
              </div>
            )}
          </article>
        ))}
      </div>
      <p className="text-xs text-slate-500 dark:text-slate-400">
        Liên kết xem tài liệu có hiệu lực 5 phút. Tải lại danh sách để lấy liên kết mới.
      </p>
    </div>
  )
}
