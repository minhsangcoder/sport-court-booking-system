'use client'
import { canPayBooking, operationsWorkspace } from '@/features/booking/ownership'
import { useMutation } from '@/features/integration/shared'
import { checkInBooking } from '@/features/operations/check-in'
import { Operations } from '@/features/operations/use-operational-detail-shared'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import { useRef, useState } from 'react'
export function useOperationalDetail({ id }: { id: string }) {
  const r = useApi<Operations>(`/bookings/${id}/operations`),
    a = useMutation(),
    { user } = useAuth(),
    access = useApi<{ facilityBindings: { facilityId: string; permissions: string[] }[] }>(
      '/users/me/access',
    ),
    owned = useApi<{ id: string }[]>(user?.roles.includes('OWNER') ? '/owner/facilities' : null)
  const [token, setToken] = useState('')
  const [paymentOpen, setPaymentOpen] = useState(false)
  const checkingIn = useRef(false)
  const can = (permission: string) =>
    Boolean(
      owned.data?.some((f) => f.id === r.data?.detail.booking.facilityId) ||
      access.data?.facilityBindings.some(
        (f) =>
          f.facilityId === r.data?.detail.booking.facilityId && f.permissions.includes(permission),
      ),
    )
  const b = r.data?.detail.booking
  const guestLabel =
    b?.source === 'OFFLINE_COUNTER'
      ? b.currentHolderId
        ? 'Khách có tài khoản'
        : 'Khách vãng lai'
      : 'Khách trực tuyến'
  const canPay = b ? canPayBooking(b, user, can('BOOKING_CREATE_COUNTER')) : false
  const workspace = b
    ? operationsWorkspace(
        user,
        b.facilityId,
        owned.data?.map((f) => f.id),
      )
    : null

  async function performCheckIn(raw: string) {
    const trimmed = raw.trim()
    if (
      !b ||
      checkingIn.current ||
      (!trimmed && !(b.source === 'OFFLINE_COUNTER' && b.currentHolderId === null))
    )
      return
    checkingIn.current = true
    try {
      await checkInBooking(b, trimmed)
      setToken('')
      r.reload()
    } finally {
      checkingIn.current = false
    }
  }

  function handleQrDetected(detectedToken: string, detectedBookingId: string | null) {
    if (detectedBookingId && detectedBookingId.toLowerCase() !== id.toLowerCase()) {
      alert(
        `Lưu ý: Mã QR này thuộc về lượt đặt #${detectedBookingId.slice(0, 8).toUpperCase()}, không khớp với lượt đặt #${id.slice(0, 8).toUpperCase()} hiện tại!`,
      )
      return
    }
    setToken(detectedToken)
    void a.run(() => performCheckIn(detectedToken))
  }

  return {
    id,
    r,
    a,
    user,
    access,
    owned,
    token,
    setToken,
    paymentOpen,
    setPaymentOpen,
    checkingIn,
    can,
    b,
    guestLabel,
    canPay,
    workspace,
    performCheckIn,
    handleQrDetected,
  }
}
