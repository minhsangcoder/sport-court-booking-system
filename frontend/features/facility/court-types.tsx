'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useFacility } from '@/features/facility/facility-store'
import {
  OwnerPageHead,
  Panel,
  primaryPill,
  fieldLabel,
  outlinePill,
} from '@/features/facility/owner-ui'
import { Status, useMutation } from '@/features/integration/shared'
import { businessApi, type CourtType } from '@/lib/business-api'
import { useApi } from '@/lib/use-api'
export function CourtTypes() {
  const { facility } = useFacility(),
    r = useApi<CourtType[]>(`/owner/facilities/${facility.id}/court-types`),
    a = useMutation()
  return (
    <section className="space-y-5">
      <OwnerPageHead
        title="Loại sân tại cơ sở"
        description="Dùng để phân loại sân và đặt giá theo loại, ví dụ sân thảm hoặc sân ngoài trời."
      />
      <Panel title="Thêm loại sân">
        <form
          className="flex flex-wrap gap-3"
          onSubmit={(e) => {
            e.preventDefault()
            const f = new FormData(e.currentTarget)
            void a.run(
              () =>
                businessApi.courtType(facility.id, { name: String(f.get('name')), active: true }),
              r.reload,
            )
          }}
        >
          <label className={`${fieldLabel} min-w-56 flex-1`}>
            Tên loại sân
            <Input name="name" required maxLength={100} />
          </label>
          <Button type="submit" className={`${primaryPill} self-end`} disabled={a.busy}>
            Thêm loại sân
          </Button>
        </form>
      </Panel>
      <Status loading={r.loading} error={r.error || a.error} empty={r.data?.length === 0} />
      {r.data?.map((t) => (
        <Panel key={t.id + ':' + t.version} title={t.name}>
          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault()
              const f = new FormData(e.currentTarget)
              void a.run(
                () =>
                  businessApi.courtType(
                    facility.id,
                    { name: String(f.get('name')), active: f.get('active') === 'on' },
                    t.id,
                  ),
                r.reload,
              )
            }}
          >
            <label className={`${fieldLabel} min-w-56 flex-1`}>
              Tên loại sân
              <Input name="name" defaultValue={t.name} required maxLength={100} />
            </label>
            <label className="flex h-11 items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-300">
              <input
                name="active"
                type="checkbox"
                defaultChecked={t.active}
                className="size-4 accent-emerald-600"
              />
              Đang dùng
            </label>
            <Button type="submit" className={outlinePill} disabled={a.busy}>
              Lưu loại sân
            </Button>
          </form>
          <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">
            Ngừng loại sân không sửa giá hoặc lượt đặt đã chốt.
          </p>
        </Panel>
      ))}
    </section>
  )
}
