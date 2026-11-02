'use client'
import { FeatureBoundary } from '@/components/feature-boundary'
import { FacilityWorkspace } from '@/features/facility/facility-store'
import type { FacilityProfile } from '@/features/facility/types'
import { Frame, Status } from '@/features/integration/shared'
import { permissions } from '@/features/operations/staff-tool-permissions.data'
import { StaffTool } from '@/features/operations/staff-tool-types'
import { useApi } from '@/lib/use-api'
import dynamic from 'next/dynamic'
import Link from 'next/link'
const SchedulePage = dynamic(() =>
  import('@/features/schedule/schedule-page').then((m) => m.SchedulePage),
)
const PreviewPage = dynamic(() =>
  import('@/features/schedule/preview-page').then((m) => m.PreviewPage),
)
const MaintenancePage = dynamic(() =>
  import('@/features/facility/maintenance').then((m) => m.MaintenancePage),
)
const CourtStatuses = dynamic(() =>
  import('@/features/operations/court-statuses').then((m) => m.CourtStatuses),
)
export function Workspace({ id, tool }: { id: string; tool: StaffTool }) {
  const access = useApi<{ facilityBindings: { facilityId: string; permissions: string[] }[] }>(
    '/users/me/access',
  )
  const codes = access.data?.facilityBindings.find((b) => b.facilityId === id)?.permissions ?? []
  const allowed = codes.includes(permissions[tool])
  const facility = useApi<FacilityProfile>(
    allowed ? `/facilities/${id}/operations/access?permission=${permissions[tool]}` : null,
  )
  const base = `/staff/facilities/${id}`
  return (
    <Frame title="Cấu hình vận hành được phân quyền">
      <Link className="font-semibold text-emerald-700 hover:underline" href="/staff/schedule">
        Về lịch cơ sở được phân công
      </Link>
      <Status loading={access.loading || facility.loading} error={access.error || facility.error} />
      {!access.loading && !allowed && (
        <p role="alert">Bạn chưa có quyền thực hiện thao tác này tại cơ sở.</p>
      )}
      {facility.data && (
        <FacilityWorkspace facility={facility.data} reload={facility.reload} basePath={base}>
          <h2 className="text-lg font-extrabold tracking-tight">{facility.data.name}</h2>
          <nav className="flex flex-wrap gap-3" aria-label="Quyền vận hành">
            {(
              [
                ['schedule', 'Lịch mở cửa'],
                ['maintenance', 'Bảo trì'],
                ['courts', 'Trạng thái sân'],
              ] as const
            )
              .filter(([key]) => codes.includes(permissions[key]))
              .map(([key, label]) => (
                <Link
                  className="rounded-full border px-4 py-2 text-sm text-emerald-700"
                  key={key}
                  href={base + '/' + key}
                >
                  {label}
                </Link>
              ))}
          </nav>
          <FeatureBoundary key={id + ':' + tool}>
            {tool === 'schedule' ? (
              <SchedulePage />
            ) : tool === 'preview' ? (
              <PreviewPage />
            ) : tool === 'maintenance' ? (
              <MaintenancePage />
            ) : (
              <CourtStatuses id={id} />
            )}
          </FeatureBoundary>
        </FacilityWorkspace>
      )}
    </Frame>
  )
}
