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
        <p role="status" className="p-8">
          Đang kiểm tra quyền đặt tại quầy…
        </p>
      )}
      {(owned.error || access.error) && (
        <p role="alert" className="p-8 text-red-700">
          {owned.error || access.error}
        </p>
      )}
      {facilityId &&
        !owned.loading &&
        !access.loading &&
        !owned.error &&
        !access.error &&
        !allowed && (
          <p role="alert" className="p-8">
            Bạn chưa có quyền đặt tại quầy ở cơ sở này.
          </p>
        )}
      {facilityId && allowed ? (
        <AvailabilityPage facilityId={facilityId} courtId={courtId} counter />
      ) : !facilityId ? (
        <p className="p-8">Thiếu cơ sở. Quay lại màn hình vận hành.</p>
      ) : null}
    </RequireAuth>
  )
}
