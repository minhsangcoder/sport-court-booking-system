'use client'

import { Button } from '@/components/ui/button'
import { RequireAuth } from '@/features/auth/require-auth'
import { Countdown } from '@/features/booking/countdown'
import { RefundPolicyNotice } from '@/features/booking/refund-policy-notice'
import type { Payment } from '@/features/booking/types'
import type { Court, Facility } from '@/features/facility/types'
import { money } from '@/features/schedule/types'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import { Check, EyeOff, RotateCcw, Trash2 } from 'lucide-react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { usePolling } from '@/lib/use-polling'
import { DeleteGroupDialog } from './delete-group-dialog'
import { GroupExtras } from './group-extras'
import { useHiddenGroups } from './hidden-groups'
import { GroupPaymentTracking } from './payment-tracking'
import { GroupPaymentSetup } from './payment-setup'
import { type Group, groupStates } from './types'

export function GroupDetail({ id }: { id: string }) {
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded id={id} />
    </RequireAuth>
  )
}

function Loaded({ id }: { id: string }) {
  const router = useRouter()
  const { user } = useAuth()
  const r = useApi<Group>(`/groups/${id}`)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [paymentKeys] = useState(() => new Map<string, string>())
  const [showDeleteModal, setShowDeleteModal] = useState(false)
  const [toastMessage, setToastMessage] = useState<string | null>(null)

  const { hide, unhide, isGroupHidden } = useHiddenGroups(user?.id)
  const hidden = isGroupHidden(id)

  const facility = useApi<Facility>(r.data ? `/facilities/${r.data.booking.facilityId}` : null)
  const courts = useApi<Court[]>(r.data ? `/facilities/${r.data.booking.facilityId}/courts` : null)

  const groupState = r.data?.state
  const bookingState = r.data?.booking.status

  usePolling(() => r.reload(), {
    enabled: Boolean(
      groupState &&
      bookingState &&
      ['GROUP_PENDING', 'CONFIRMED'].includes(groupState) &&
      !['COMPLETED', 'CANCELLED', 'EXPIRED'].includes(bookingState) &&
      !r.error,
    ),
    scope: id,
    interval: 5000,
    deadline: r.data ? Date.parse(r.data.booking.endsAt) : undefined,
  })

  function showToast(msg: string) {
    setToastMessage(msg)
    setTimeout(() => setToastMessage(null), 3000)
  }

  async function pay(memberId: string) {
    if (busy) return
    setBusy(true)
    setError('')
    if (!paymentKeys.has(memberId)) paymentKeys.set(memberId, crypto.randomUUID())
    try {
      const p = await api<Payment>('/payments', {
        method: 'POST',
        headers: { 'Idempotency-Key': paymentKeys.get(memberId)! },
        body: JSON.stringify({ bookingId: id, memberId }),
      })
      if (p.status === 'FAILED' || p.status === 'EXPIRED') {
        paymentKeys.delete(memberId)
        throw new Error('Giao dịch trước đã kết thúc. Bấm thanh toán để tạo giao dịch mới.')
      }
      router.push(`/customer/payments/${p.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể tạo giao dịch.')
    } finally {
      setBusy(false)
    }
  }

  async function act(action: 'leave' | 'cancel') {
    if (
      !window.confirm(
        action === 'leave' ? 'Rời nhóm và yêu cầu chia lại chi phí?' : 'Hủy nhóm đặt sân này?',
      )
    )
      return
    setBusy(true)
    setError('')
    try {
      await api(`/groups/${id}/${action}`, {
        method: 'POST',
        body:
          action === 'cancel'
            ? JSON.stringify({ reason: 'Người tổ chức hủy lượt đặt nhóm chưa thanh toán' })
            : undefined,
      })
      if (action === 'leave') router.push('/customer/groups')
      else r.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể cập nhật nhóm.')
    } finally {
      setBusy(false)
    }
  }

  function handleConfirmDelete() {
    hide(id)
    setShowDeleteModal(false)
    showToast('Đã xóa nhóm khỏi danh sách cá nhân của bạn')
    setTimeout(() => {
      router.push('/customer/groups')
    }, 600)
  }

  function handleRestoreGroup() {
    unhide(id)
    showToast('Đã khôi phục nhóm vào danh sách hiển thị')
  }

  if (r.loading && !r.data)
    return (
      <p className="p-8" role="status">
        Đang tải nhóm…
      </p>
    )
  if (!r.data)
    return (
      <p className="p-8 text-red-700" role="alert">
        {r.error}
        <button className="ml-4 underline" onClick={r.reload}>
          Thử lại
        </button>
      </p>
    )

  const g = r.data
  const owner = user?.id === g.ownerId
  const pending = g.state === 'GROUP_PENDING'
  const total = g.booking.amount
  const locked = g.members.some((m) => m.amountPaid > 0 || Boolean(m.paymentRequestedAt))

  return (
    <div className="mx-auto max-w-4xl px-4 sm:px-6 py-8 sm:py-10">
      {/* Delete confirmation dialog */}
      <DeleteGroupDialog
        isOpen={showDeleteModal}
        onClose={() => setShowDeleteModal(false)}
        onConfirm={handleConfirmDelete}
        groupName={g.name}
      />

      {/* Breadcrumb */}
      <div className="flex items-center justify-between">
        <Link
          className="text-sm text-slate-500 hover:text-slate-900 transition"
          href="/customer/groups"
        >
          Nhóm đặt sân / {g.name}
        </Link>
      </div>

      {/* Hidden banner if group is currently hidden */}
      {hidden && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-amber-50 p-4 border border-amber-200 text-sm text-amber-900 animate-in fade-in">
          <div className="flex items-center gap-2">
            <EyeOff className="h-4 w-4 text-amber-700 shrink-0" />
            <span>
              Nhóm này hiện đang <strong>bị ẩn khỏi danh sách cá nhân</strong> của bạn.
            </span>
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleRestoreGroup}
            className="gap-1.5 rounded-full border-amber-300 bg-white text-xs font-semibold text-amber-900 hover:bg-amber-100"
          >
            <RotateCcw className="h-3.5 w-3.5" />
            <span>Khôi phục nhóm</span>
          </Button>
        </div>
      )}

      {/* Header with Title and Actions */}
      <div className="mt-5 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl sm:text-3xl font-black text-slate-900">{g.name}</h1>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <span className="rounded-full bg-emerald-50 px-3.5 py-1.5 text-xs sm:text-sm font-semibold text-emerald-700 border border-emerald-100">
            {groupStates[g.state]}
          </span>

          {/* Delete from my personal groups button */}
          {!hidden ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setShowDeleteModal(true)}
              className="gap-1.5 rounded-full border-slate-200 text-xs font-semibold text-slate-600 hover:bg-rose-50 hover:text-rose-700 hover:border-rose-200 transition"
              title="Xóa nhóm khỏi danh sách của tôi"
            >
              <Trash2 className="h-3.5 w-3.5 text-slate-400 hover:text-rose-600" />
              <span>Xóa nhóm</span>
            </Button>
          ) : (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleRestoreGroup}
              className="gap-1.5 rounded-full border-slate-200 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 transition"
            >
              <RotateCcw className="h-3.5 w-3.5" />
              <span>Khôi phục nhóm</span>
            </Button>
          )}
        </div>
      </div>

      {/* Facility & Match Information */}
      <section className="mt-6 rounded-2xl border border-slate-200 bg-white p-5 sm:p-6 shadow-xs">
        <h2 className="text-xl font-bold text-slate-900">
          {facility.data?.name || 'Cơ sở'} ·{' '}
          {courts.data?.find((c) => c.id === g.booking.courtId)?.name || 'Sân'}
        </h2>
        <p className="mt-2 text-sm text-slate-500">
          {new Date(g.booking.startsAt).toLocaleString('vi-VN', {
            timeZone: g.booking.priceSnapshot.timezone,
          })}{' '}
          →{' '}
          {new Date(g.booking.endsAt).toLocaleTimeString('vi-VN', {
            timeZone: g.booking.priceSnapshot.timezone,
          })}
        </p>

        {pending && (
          <div className="mt-4">
            <Countdown expiresAt={g.deadline} />
          </div>
        )}

        <p className="mt-5 font-bold text-slate-900">
          Đã đóng {money(g.totalPaid)} / {money(total)}
        </p>
        <progress
          className="mt-3 h-3 w-full accent-emerald-600"
          value={g.totalPaid}
          max={total}
          aria-label="Tiến độ thanh toán nhóm"
        />
        <p className="mt-3 text-sm text-slate-500">
          {g.allocationsLocked
            ? 'Danh sách đã chốt; mỗi người thanh toán phần được phân bổ.'
            : 'Chờ thành viên tham gia và người tổ chức chia tiền.'}
        </p>
      </section>

      {/* Owner Pending Actions */}
      {owner && pending && !locked && <GroupPaymentSetup group={g} reload={r.reload} />}
      {/* Errors */}
      {(error || r.error) && (
        <p
          role="alert"
          className="mt-5 rounded-xl bg-red-50 p-4 text-sm text-red-700 border border-red-100"
        >
          {error || r.error}
        </p>
      )}

      {/* Payment Tracking */}
      <RefundPolicyNotice />
      <GroupPaymentTracking group={g} userId={user?.id} busy={busy} pay={pay} reload={r.reload} />

      {/* Member leave button */}
      {!owner && pending && (
        <Button
          className="mt-5 rounded-full"
          variant="outline"
          disabled={busy || locked}
          onClick={() => act('leave')}
        >
          Rời nhóm
        </Button>
      )}

      {/* Confirmed checkin link */}
      {g.state === 'CONFIRMED' && owner && (
        <Link
          className="mt-6 block rounded-2xl bg-emerald-600 p-4 text-center font-bold text-white shadow-xs hover:bg-emerald-700"
          href={`/customer/bookings/${id}`}
        >
          Mở lượt đặt và mã xác nhận vào sân
        </Link>
      )}

      {/* Expired state notice with prominent Delete Action */}
      {g.state === 'GROUP_EXPIRED' && (
        <div className="mt-6 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 rounded-2xl bg-amber-50/90 p-5 border border-amber-200 text-sm text-amber-900">
          <p className="leading-relaxed">
            Sân đã được giải phóng. Khoản đóng góp đã nhận được lưu để đối soát và xem xét hoàn tiền
            theo chính sách được duyệt.
          </p>
          {!hidden && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setShowDeleteModal(true)}
              className="shrink-0 gap-1.5 rounded-full border-amber-300 bg-white text-xs font-semibold text-amber-900 hover:bg-rose-50 hover:text-rose-700 hover:border-rose-200 shadow-xs"
            >
              <Trash2 className="h-3.5 w-3.5 text-rose-600" />
              <span>Xóa khỏi danh sách của tôi</span>
            </Button>
          )}
        </div>
      )}

      {/* Social, chat and extras */}
      <GroupExtras group={g} />

      {/* Floating feedback toast */}
      {toastMessage && (
        <div
          role="status"
          aria-live="polite"
          className="fixed bottom-6 left-1/2 z-50 -translate-x-1/2 flex items-center gap-2 rounded-full bg-slate-900 px-5 py-2.5 text-sm font-medium text-white shadow-xl animate-in fade-in slide-in-from-bottom-3 duration-200"
        >
          <Check className="h-4 w-4 text-emerald-400" />
          <span>{toastMessage}</span>
        </div>
      )}
    </div>
  )
}
