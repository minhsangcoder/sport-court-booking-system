'use client'
import { RequireAuth } from '@/features/auth/require-auth'
import { AvailabilityPage } from '@/features/discovery/availability-page'
import { useSearchParams } from 'next/navigation'
import { useApi } from '@/lib/use-api'
import type { Facility } from '@/features/facility/types'
import type { Access } from './operation-types'
export function CounterSlots({ courtId, owner = false }: { courtId: string; owner?: boolean }) {
  const params = useSearchParams()
  const facilityId = params.get('facilityId')
  const owned = useApi<Facility>(owner && facilityId ? `/owner/facilities/${facilityId}` : null)
  const access = useApi<Access>(owner ? null : '/users/me/access')
  const allowed = owner
    ? owned.data?.id === facilityId
    : access.data?.facilityBindings.some(
        (binding) =>
          binding.facilityId === facilityId &&
          binding.permissions.includes('BOOKING_CREATE_COUNTER'),
      )
  return (
    <RequireAuth roles={[owner ? 'OWNER' : 'STAFF']}>
      {(owned.loading || access.loading) && (
        <div className="flex min-h-[40vh] items-center justify-center p-8">
          <p role="status" className="flex items-center gap-2 text-sm font-semibold text-slate-500">
            Đang kiểm tra quyền đặt tại quầy…
          </p>
        </div>
      )}
      {(owned.error || access.error) && (
        <div className="mx-auto max-w-4xl p-6">
          <p
            role="alert"
            className="rounded-2xl border border-red-200 bg-red-50 p-5 text-sm font-medium text-red-700 shadow-2xs dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
          >
            {owned.error || access.error}
          </p>
        </div>
      )}
      {facilityId &&
        !owned.loading &&
        !access.loading &&
        !owned.error &&
        !access.error &&
        !allowed && (
          <div className="mx-auto max-w-4xl p-6">
            <p
              role="alert"
              className="rounded-2xl border border-amber-200 bg-amber-50 p-5 text-sm font-medium text-amber-800 shadow-2xs dark:border-amber-900/50 dark:bg-amber-950/30 dark:text-amber-300"
            >
              Bạn chưa có quyền đặt tại quầy ở cơ sở này.
            </p>
          </div>
        )}
      {facilityId && allowed ? (
        <AvailabilityPage facilityId={facilityId} courtId={courtId} counter />
      ) : !facilityId ? (
        <div className="mx-auto max-w-4xl p-6">
          <p className="rounded-2xl border border-slate-200 bg-slate-50 p-5 text-sm font-medium text-slate-600 shadow-2xs">
            Thiếu cơ sở. Quay lại màn hình vận hành.
          </p>
        </div>
      ) : null}
    </RequireAuth>
  )
}
