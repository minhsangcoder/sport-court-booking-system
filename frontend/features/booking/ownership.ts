import type { UserProfile } from '@/lib/api'
import type { Booking, Payment } from './types'

type Actor = Pick<UserProfile, 'id' | 'roles'> | null

export function ownsBooking(booking: Booking, actor: Actor): boolean {
  return Boolean(
    actor?.roles.includes('CUSTOMER') &&
    booking.currentHolderId &&
    booking.currentHolderId === actor.id,
  )
}

export function operationsWorkspace(
  actor: Actor,
  facilityId: string,
  ownedFacilityIds: readonly string[] = [],
): 'owner' | 'staff' | null {
  if (actor?.roles.includes('OWNER') && ownedFacilityIds.includes(facilityId)) return 'owner'
  return actor?.roles.includes('STAFF') ? 'staff' : null
}

export function bookingResultPath(
  payment: Payment,
  booking: Booking | undefined,
  actor: Actor,
  ownedFacilityIds?: readonly string[],
): string | null {
  if (payment.purpose === 'TRANSFER')
    return `/customer/transfers/acquisitions/${payment.acquisitionId}`
  if (payment.purpose === 'GROUP_CONTRIBUTION') return `/customer/groups/${payment.bookingId}`
  if (!booking || !actor) return null
  if (ownsBooking(booking, actor)) return `/customer/bookings/${booking.id}`
  if (booking.source !== 'OFFLINE_COUNTER' || booking.createdBy !== actor.id) {
    return actor.roles.includes('CUSTOMER') ? `/customer/bookings/${booking.id}` : null
  }
  if (actor.roles.includes('OWNER') && !ownedFacilityIds) return null
  const workspace = operationsWorkspace(actor, booking.facilityId, ownedFacilityIds)
  return workspace ? `/${workspace}/bookings/${booking.id}` : null
}

export function canPayBooking(booking: Booking, actor: Actor, canCreateCounter: boolean): boolean {
  return (
    ownsBooking(booking, actor) ||
    Boolean(
      booking.source === 'OFFLINE_COUNTER' && actor?.id === booking.createdBy && canCreateCounter,
    )
  )
}
