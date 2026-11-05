const uuid = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
const routes = [
  `^/customer/(favorites|staff-invitations)$`,
  `^/customer/groups/play-groups/${uuid}$`,
  `^/owner/facilities/${uuid}/court-types$`,
  `^/owner/application/${uuid}/facility/court-types$`,
  `^/owner/bookings/${uuid}$`,
  `^/staff/bookings/${uuid}$`,
  `^/staff/facilities/${uuid}/(schedule(?:/preview)?|maintenance|courts)$`,
  `^/admin/sports$`,
  '^/(owner|staff|admin)$',
]
export function isFrontendRoute(path: string) {
  return routes.some((r) => new RegExp(r).test(path))
}
