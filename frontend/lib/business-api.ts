import { api } from './api'
import type { Booking, Payment } from '@/features/booking/types'
import type { Group } from '@/features/groups/types'
export type StaffProfile = {
  id: string
  facilityId: string
  fullName: string
  email?: string
  phone?: string
  active: boolean
  linked: boolean
  bindingId?: string
  userId?: string
  permissions: string[]
  version: number
}
export type StaffInvitation = {
  id: string
  profileId: string
  facilityId: string
  staffName: string
  permissions: string[]
  state: string
  expiresAt: string
}
export type PermissionView = { bindingId: string; permissions: string[]; version: number }
export type CourtType = {
  id: string
  facilityId: string
  name: string
  active: boolean
  version: number
}
export type Favorite = {
  facilityId: string
  favorite: boolean
  addedAt?: string
  available: boolean
  facility?: { id: string; name: string; addressLine: string; ward: string; province: string }
}
export type MemberTicket = {
  groupId: string
  memberId: string
  token: string
  qrSvg: string
  qrPngDataUrl: string
  expiresAt: string
}
export type Attendance = {
  member_id: string
  display_name: string
  checked_in_at?: string
  checked_in_by?: string
  method?: string
}
export type PlayGroup = {
  id: string
  owner_id: string
  state: 'OPEN' | 'KEPT' | 'DISSOLVED'
  version: number
  members: { user_id: string; display_name: string }[]
  bookingGroupIds: string[]
}
export type ChatMessage = {
  id: number
  author_id: string
  author_name?: string
  display_name?: string
  body: string
  created_at: string
}
export type RefundReview = {
  id: string
  payment_id: string
  requester_id: string
  beneficiary_id: string
  state: string
  amount?: number
  reason: string
  reviews?: { id: string; actor_id: string; note: string; created_at: string }[]
}
export type SupportCase = {
  id: string
  booking_id: string
  category: string
  subject: string
  description?: string
  evidence?: string
  state: string
  version: number
  financialExecution?: string
  notificationStatus?: string
  history?: { id: number; action: string; note: string; occurred_at: string }[]
}
export type SupportInput = {
  bookingId: string
  reportedBy?: string
  category: 'FACILITY_QUALITY' | 'PAYMENT_REVIEW' | 'TRANSFER_REVIEW'
  subject: string
  description: string
  evidence?: string
  paymentId?: string
  listingId?: string
}
export type StatusImpact = {
  facilityId: string
  status: string
  version: number
  impactFingerprint: string
  reservations: { id: string; courtId: string; startsAt: string; endsAt: string; state: string }[]
}
export type Sport = {
  id: string
  name: string
  active: boolean
  description?: string
  icon?: string
  version: number
  court_count?: number
}
const write = <T>(path: string, body: unknown, key?: string, method = 'POST') =>
  api<T>(path, {
    method,
    body: JSON.stringify(body),
    headers: key ? { 'Idempotency-Key': key } : undefined,
  })
export const businessApi = {
  favorite: (id: string, save: boolean) =>
    api<Favorite | void>(`/facilities/${id}/favorite`, { method: save ? 'PUT' : 'DELETE' }),
  profile: (
    facility: string,
    body: {
      fullName: string
      email?: string
      phone?: string
      active: boolean
      expectedVersion?: number
    },
    id?: string,
  ) =>
    write<StaffProfile>(
      `/staff-profiles/facilities/${facility}${id ? '/' + id : ''}`,
      body,
      undefined,
      id ? 'PUT' : 'POST',
    ),
  createStaffAccount: (
    facility: string,
    body: {
      fullName: string
      email: string
      phone?: string
      password: string
      permissions: string[]
    },
  ) =>
    write<StaffProfile>(`/staff-profiles/facilities/${facility}/accounts`, body, undefined, 'POST'),
  resetStaffPassword: (facility: string, profileId: string, newPassword: string) =>
    api(`/staff-profiles/facilities/${facility}/${profileId}/password`, {
      method: 'PUT',
      body: JSON.stringify({ newPassword }),
    }),
  inviteStaff: (f: string, id: string, identifier: string, permissions: string[]) =>
    write<StaffInvitation>(`/staff-profiles/facilities/${f}/${id}/invitations`, {
      identifier,
      permissions,
    }),
  lookupStaff: (f: string, identifier: string) =>
    write<{ userId: string; fullName: string; maskedIdentifier: string }>(
      `/staff-bindings/facilities/${f}/account-lookup`,
      { identifier },
    ),
  bindStaff: (f: string, identifier: string, permissions: string[]) =>
    write(`/staff-bindings/facilities/${f}`, { identifier, permissions }),
  invitation: (id: string, action: 'accept' | 'decline') =>
    api(`/users/me/staff-invitations/${id}/${action}`, { method: 'POST' }),
  permissions: (f: string, id: string, permissions: string[], version: number) =>
    write<PermissionView>(
      `/staff-bindings/facilities/${f}/${id}/permissions`,
      { permissions, expectedVersion: version },
      undefined,
      'PUT',
    ),
  courtType: (f: string, body: { name: string; active: boolean }, id?: string) =>
    write<CourtType>(
      `/owner/facilities/${f}/court-types${id ? '/' + id : ''}`,
      body,
      undefined,
      id ? 'PUT' : 'POST',
    ),
  courtStatus: (id: string, enabled: boolean) =>
    write(`/owner/courts/${id}/status`, { enabled }, undefined, 'PATCH'),
  scanMember: (id: string, token: string) => write(`/groups/${id}/attendance/scan`, { token }),
  manualMember: (id: string, memberId: string, reason: string) =>
    write(`/groups/${id}/attendance/manual`, { memberId, reason }),
  message: (id: string, body: string, key: string) =>
    write<ChatMessage>(`/groups/play-groups/${id}/messages`, { body }, key),
  playAction: (id: string, action: 'KEEP' | 'DISSOLVE', expectedVersion: number) =>
    write<PlayGroup>(`/groups/play-groups/${id}/actions`, { action, expectedVersion }),
  leavePlay: (id: string) => api(`/groups/play-groups/${id}/leave`, { method: 'POST' }),
  reuse: (id: string, holdId: string, name: string, maxMembers: number, key: string) =>
    write<{
      bookingGroup: Group
      suggestedMembers: { user_id: string; display_name: string }[]
      participationPolicy: string
    }>(`/groups/play-groups/${id}/reuse`, { holdId, name, maxMembers }, key),
  support: (body: SupportInput, key: string) =>
    write<SupportCase>('/admin/bookings/complaints', body, key),
  supportDecision: (
    id: string,
    action: 'START_REVIEW' | 'REQUEST_INFORMATION' | 'RECORD_DECISION',
    note: string,
    expectedVersion: number,
  ) =>
    write<SupportCase>(`/admin/bookings/complaints/${id}/decisions`, {
      action,
      note,
      expectedVersion,
    }),
  refundNote: (id: string, note: string, key: string) =>
    write(`/admin/payments/refunds/${id}/reviews`, { note }, key),
  statusDecision: (
    id: string,
    impact: StatusImpact,
    action: 'SUSPEND' | 'RESTORE',
    reason: string,
  ) =>
    write(`/admin/facilities/${id}/status-decisions`, {
      action,
      reason,
      expectedVersion: impact.version,
      impactFingerprint: impact.impactFingerprint,
    }),
  sport: (
    body: {
      name: string
      active: boolean
      description?: string
      icon?: string
      expectedVersion?: number
    },
    id?: string,
  ) =>
    write<Sport>(
      `/admin/sport-categories${id ? '/' + id : ''}`,
      body,
      undefined,
      id ? 'PUT' : 'POST',
    ),
  accountState: (id: string, state: 'suspend' | 'deactivate' | 'reactivate', reason: string) =>
    write(`/admin/accounts/${id}/${state}`, { reason }),
  paymentRetry: (id: string) => api(`/payments/${id}/synchronization/retry`, { method: 'POST' }),
  counter: (
    body: {
      courtId: string
      startsAt: string
      endsAt: string
      expectedAmount: number
      guestName: string
      guestPhone: string
      paymentMethod: 'DEMO' | 'BANK_QR'
      refundPolicyHash?: string
    },
    key: string,
  ) => write<Booking>('/bookings/counter', body, key),
  pay: (bookingId: string, key: string, memberId?: string) =>
    write<Payment>('/payments', { bookingId, memberId }, key),
}
