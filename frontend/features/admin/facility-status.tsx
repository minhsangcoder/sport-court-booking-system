'use client'
import { confirmDanger } from '@/components/confirm-dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, Status, useMutation } from '@/features/integration/shared'
import { businessApi, type StatusImpact } from '@/lib/business-api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
export function FacilityStatus({ id, status }: { id: string; status: string }) {
  const r = useApi<StatusImpact>(
      ['ACTIVE', 'SUSPENDED'].includes(status) ? `/admin/facilities/${id}/status-impact` : null,
    ),
    a = useMutation()
  const [checkedAt, setCheckedAt] = useState<Date | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  if (!['ACTIVE', 'SUSPENDED'].includes(status)) return null
  return (
    <Card title="Tạm ngừng / khôi phục cơ sở">
      <Status loading={r.loading} error={r.error || a.error} />
      {r.data && (
        <>
          <p className="text-sm text-slate-500">
            {r.data.reservations.length} lượt giữ chỗ có liên quan. Không tự hủy lượt đặt đã xác
            nhận hoặc thực hiện hoàn tiền.
          </p>
          {r.data.reservations.length > 0 && (
            <ul className="space-y-1.5 text-sm">
              {r.data.reservations.map((x) => (
                <li key={x.id} className="rounded-xl bg-slate-50 px-3 py-2 dark:bg-slate-800">
                  #{x.id.slice(0, 8).toUpperCase()} · {new Date(x.startsAt).toLocaleString('vi-VN')}{' '}
                  – {new Date(x.endsAt).toLocaleTimeString('vi-VN')}
                </li>
              ))}
            </ul>
          )}
          {checkedAt && (
            <p role="status" className="text-xs font-medium text-emerald-700">
              {refreshing
                ? 'Đang cập nhật bản xem trước…'
                : `Đã cập nhật lúc ${checkedAt.toLocaleTimeString('vi-VN')} · ${r.data.reservations.length} lượt giữ chỗ bị ảnh hưởng`}
            </p>
          )}
          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={async (e) => {
              e.preventDefault()
              const f = new FormData(e.currentTarget)
              if (
                !(await confirmDanger('Xác nhận thay đổi trạng thái theo bản xem trước hiện tại?'))
              )
                return
              void a.run(
                () =>
                  businessApi.statusDecision(
                    id,
                    r.data!,
                    status === 'ACTIVE' ? 'SUSPEND' : 'RESTORE',
                    String(f.get('reason')),
                  ),
                () => window.location.reload(),
              )
            }}
          >
            <label className="grid min-w-64 flex-1 gap-2 text-sm">
              Lý do quyết định
              <Input name="reason" required minLength={20} maxLength={2000} />
            </label>
            <div className="flex flex-wrap gap-3">
              <Button type="submit" disabled={a.busy}>
                {status === 'ACTIVE' ? 'Tạm ngừng cơ sở' : 'Khôi phục cơ sở'}
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={refreshing}
                onClick={() => {
                  setRefreshing(true)
                  setCheckedAt(new Date())
                  void r.reload().finally(() => {
                    setCheckedAt(new Date())
                    setRefreshing(false)
                  })
                }}
                title="Tải lại danh sách lượt giữ chỗ bị ảnh hưởng trước khi quyết định"
              >
                {refreshing ? 'Đang cập nhật…' : 'Cập nhật bản xem trước'}
              </Button>
            </div>
          </form>
        </>
      )}
    </Card>
  )
}
