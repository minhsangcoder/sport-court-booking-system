'use client'
import { Button } from '@/components/ui/button'
import { api } from '@/lib/api'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
import { useFacility } from './facility-store'
import {
  OwnerPageHead,
  Panel,
  amberNotice,
  card,
  emeraldNotice,
  emptyPanel,
  outlinePill,
  primaryPill,
  redNotice,
} from './owner-ui'
export type FacilityReview = {
  id: string
  facilityId: string
  ownerId: string
  state: string
  snapshot: unknown
  submittedAt: string
  reviewedAt: string | null
  reviewedBy: string | null
  reason: string | null
}
export const reviewLabels: Record<string, string> = {
  DRAFT: 'Bản nháp',
  PENDING_APPROVAL: 'Chờ kiểm duyệt',
  ACTIVE: 'Đang hoạt động',
  APPROVED: 'Đã phê duyệt',
  REJECTED: 'Đã từ chối',
  SUPPLEMENT_REQUIRED: 'Cần bổ sung',
  SUSPENDED: 'Tạm ngừng',
}
export function FacilityReviewPage() {
  const { facility, reload } = useFacility()
  const r = useApi<FacilityReview[]>(`/owner/facilities/${facility.id}/reviews`)
  const [confirm, setConfirm] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit() {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await api(`/owner/facilities/${facility.id}/submit`, { method: 'POST' })
      setConfirm(false)
      r.reload()
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể gửi hồ sơ.')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="space-y-6">
      <OwnerPageHead
        title="Kiểm duyệt cơ sở"
        description="Hoàn thiện hồ sơ, thêm sân và khai báo lịch mở cửa trước khi gửi. Hồ sơ được giữ nguyên trong thời gian quản trị viên thẩm định."
      />
      <Panel title={reviewLabels[facility.status] || displayLabel(facility.status)}>
        <div className="flex flex-wrap gap-2.5">
          <Link href={`/owner/facilities/${facility.id}/edit`} className={outlinePill}>
            Thông tin cơ sở
          </Link>
          <Link href={`/owner/facilities/${facility.id}/courts`} className={outlinePill}>
            Danh sách sân
          </Link>
          <Link href={`/owner/facilities/${facility.id}/schedule`} className={outlinePill}>
            Lịch mở cửa
          </Link>
          <Link href={`/owner/facilities/${facility.id}/documents`} className={outlinePill}>
            Tài liệu thẩm định riêng tư
          </Link>
        </div>
        {facility.status === 'DRAFT' && (
          <Button onClick={() => setConfirm(true)} className={`${primaryPill} mt-5`}>
            Gửi kiểm duyệt
          </Button>
        )}
        {facility.status === 'REJECTED' && (
          <p className={`${amberNotice} mt-4`}>
            Hồ sơ đã bị từ chối. Tạo một cơ sở mới để nộp lại thông tin đầy đủ.
          </p>
        )}
      </Panel>
      {confirm && (
        <section role="dialog" aria-label="Xác nhận gửi hồ sơ" className={emeraldNotice}>
          <h3 className="text-base font-extrabold">Gửi hồ sơ {facility.name}</h3>
          <p className="mt-2 font-normal">
            Quản trị viên sẽ xem thông tin cơ sở, sân, lịch mở cửa và tài liệu đính kèm. Bạn có thể
            sửa hồ sơ khi quản trị viên yêu cầu bổ sung.
          </p>
          <div className="mt-4 flex flex-wrap gap-3">
            <Button onClick={submit} disabled={busy} className={primaryPill}>
              {busy ? 'Đang gửi…' : 'Xác nhận gửi'}
            </Button>
            <Button
              variant="outline"
              className={outlinePill}
              onClick={() => setConfirm(false)}
              disabled={busy}
            >
              Quay lại
            </Button>
          </div>
        </section>
      )}
      {error && (
        <p role="alert" className={redNotice}>
          {error}
        </p>
      )}
      {r.loading && (
        <p role="status" className="text-sm text-slate-500">
          Đang tải lịch sử kiểm duyệt…
        </p>
      )}
      {r.error && (
        <p role="alert" className={redNotice}>
          {r.error}
          <button className="ml-4 underline" onClick={r.reload}>
            Thử lại
          </button>
        </p>
      )}
      <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
        Lịch sử hồ sơ
      </h3>
      {r.data?.length === 0 && <p className={emptyPanel}>Cơ sở chưa gửi hồ sơ kiểm duyệt.</p>}
      <div className="space-y-4">
        {r.data?.map((review) => (
          <article key={review.id} className={`${card} home-rise p-5`}>
            <p className="font-bold text-slate-950 dark:text-white">
              {reviewLabels[review.state] || displayLabel(review.state)}
            </p>
            <p className="mt-2 text-xs text-slate-500">
              Nộp {new Date(review.submittedAt).toLocaleString('vi-VN')}
              {review.reviewedAt &&
                ` · Xử lý ${new Date(review.reviewedAt).toLocaleString('vi-VN')}`}
            </p>
            {review.reason && <p className="mt-4 text-sm">{review.reason}</p>}
          </article>
        ))}
      </div>
    </div>
  )
}
