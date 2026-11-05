'use client'
import dynamic from 'next/dynamic'
import { RequireAuth } from '@/features/auth/require-auth'
import { FeatureBoundary } from './feature-boundary'
const loading = () => (
  <p className="p-8" role="status">
    Đang mở màn hình…
  </p>
)
const FavoritesPage = dynamic(
  () => import('@/features/integration/favorites').then((module) => module.FavoritesPage),
  { loading },
)
const StaffInvitations = dynamic(
  () => import('@/features/staff/staff-invitations').then((module) => module.StaffInvitations),
  { loading },
)
const KeptGroupPage = dynamic(
  () => import('@/features/groups/kept-group-page').then((module) => module.KeptGroupPage),
  { loading },
)
const SportsPage = dynamic(
  () => import('@/features/admin/sports-page').then((module) => module.SportsPage),
  { loading },
)
const FacilityCourtTypesRoute = dynamic(
  () =>
    import('@/features/facility/court-types-route').then(
      (module) => module.FacilityCourtTypesRoute,
    ),
  { loading },
)
const ApplicationCourtTypes = dynamic(
  () =>
    import('@/features/owner-application/application-court-types').then(
      (module) => module.ApplicationCourtTypes,
    ),
  { loading },
)
const FacilityListPage = dynamic(
  () => import('@/features/facility/facility-list').then((module) => module.FacilityListPage),
  { loading },
)
const OperationBookings = dynamic(
  () => import('@/features/operations/bookings-page').then((module) => module.OperationBookings),
  { loading },
)
const OperationalDetail = dynamic(
  () =>
    import('@/features/operations/operational-detail').then((module) => module.OperationalDetail),
  { loading },
)
const ReportPage = dynamic(
  () => import('@/features/admin/report-page').then((module) => module.ReportPage),
  { loading },
)
const StaffConfiguration = dynamic(
  () =>
    import('@/features/integration/staff-configuration').then(
      (module) => module.StaffConfiguration,
    ),
  { loading },
)
export function RouteView({ parts }: { parts: string[] }) {
  return (
    <FeatureBoundary key={parts.join('/')}>
      <ResolvedRoute parts={parts} />
    </FeatureBoundary>
  )
}
function ResolvedRoute({ parts: p }: { parts: string[] }) {
  if (p[0] === 'customer') {
    if (p[1] === 'favorites') return <FavoritesPage />
    if (p[1] === 'staff-invitations') return <StaffInvitations />
    return (
      <RequireAuth roles={['CUSTOMER']}>
        <KeptGroupPage id={p[3]} />
      </RequireAuth>
    )
  }
  if (p[0] === 'admin') {
    const child = p[1] === 'sports' ? <SportsPage /> : <ReportPage />
    return <RequireAuth roles={['ADMIN']}>{child}</RequireAuth>
  }
  if (p[0] === 'owner' && p[1] === 'facilities') return <FacilityCourtTypesRoute id={p[2]} />
  if (p[0] === 'owner' && p[1] === 'application')
    return (
      <RequireAuth roles={['CUSTOMER', 'OWNER']}>
        <ApplicationCourtTypes id={p[2]} />
      </RequireAuth>
    )
  if (p[0] === 'staff' && p[1] === 'facilities')
    return (
      <StaffConfiguration
        id={p[2]}
        tool={p[4] === 'preview' ? 'preview' : (p[3] as 'schedule' | 'courts' | 'maintenance')}
      />
    )
  if (p[1] === 'bookings' && p[2])
    return (
      <RequireAuth roles={[p[0] === 'owner' ? 'OWNER' : 'STAFF']}>
        <OperationalDetail id={p[2]} />
      </RequireAuth>
    )
  return p[0] === 'owner' ? <FacilityListPage /> : <OperationBookings />
}
