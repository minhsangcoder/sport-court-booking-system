'use client'

import { PageBanner } from '@/components/page-banner'
import { Button } from '@/components/ui/button'
import { RequireAuth } from '@/features/auth/require-auth'
import { Countdown } from '@/features/booking/countdown'
import { RefundPolicyNotice } from '@/features/booking/refund-policy-notice'
import type { Payment } from '@/features/booking/types'
import type { Court, Facility } from '@/features/facility/types'
import { CourtLines } from '@/features/home/home-art'
import { money } from '@/features/schedule/types'
import { amberNotice, bannerLink, card, primaryPill, statePill } from '@/features/transfers/transfer-styles'
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
import { type ContributionClaim, type Group, groupStates } from './types'

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

  const claimPath =
    r.data?.state === 'GROUP_PENDING' && r.data.allocationsLocked && r.data.members.length
      ? `/payments/group-claims?bookingId=${id}&${r.data.members.map((m) => `memberId=${m.id}`).join('&')}`
      : null
  const claims = useApi<ContributionClaim[]>(claimPath)

  const groupState = r.data?.state
  const bookingState = r.data?.booking.status

  usePolling(() => {
    r.reload()
    if (claimPath) claims.reload()
  }, {
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
    <>
      <PageBanner
        eyebrow={
          <Link className={bannerLink} href="/customer/groups">
            Nhóm đặt sân
          </Link>
        }
        title={g.name}
        description={`${facility.data?.name || 'Cơ sở'} · ${courts.data?.find((c) => c.id === g.booking.courtId)?.name || 'Sân'}`}
        icon="shuttle"
        actions={
          <>
            <span className={statePill}>{groupStates[g.state]}</span>
            {!hidden ? (
              <button
                type="button"
                onClick={() => setShowDeleteModal(true)}
                title="Xóa nhóm khỏi danh sách của tôi"
                className="inline-flex h-10 items-center gap-1.5 rounded-full border border-white/30 bg-white/10 px-5 text-sm font-semibold text-white backdrop-blur-md transition hover:bg-rose-500/30"
              >
                <Trash2 className="h-3.5 w-3.5" />
                <span>Xóa nhóm</span>
              </button>
            ) : (
              <button
                type="button"
                onClick={handleRestoreGroup}
                className="inline-flex h-10 items-center gap-1.5 rounded-full border border-white/30 bg-white/10 px-5 text-sm font-semibold text-white backdrop-blur-md transition hover:bg-white/20"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                <span>Khôi phục nhóm</span>
              </button>
            )}
          </>
        }
      />
    <div className="mx-auto max-w-5xl space-y-6 px-4 py-8 sm:px-6 sm:py-10">
      {/* Delete confirmation dialog */}
      <DeleteGroupDialog
        isOpen={showDeleteModal}
        onClose={() => setShowDeleteModal(false)}
        onConfirm={handleConfirmDelete}
        groupName={g.name}
      />

      {/* Hidden banner if group is currently hidden */}
      {hidden && (
        <div className={`${amberNotice} flex flex-wrap items-center justify-between gap-3`}>
          <div className="flex items-center gap-2">
            <EyeOff className="h-4 w-4 shrink-0 text-amber-700" />
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

      {/* Facility & Match Information */}
      <section className={`${card} home-rise p-5 sm:p-6`}>
        <CourtLines className="pointer-events-none absolute -right-20 -top-12 -z-10 w-80 rotate-[-6deg] text-emerald-600/[0.05]" />
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-xl font-extrabold tracking-tight text-slate-950 dark:text-white">
              {facility.data?.name || 'Cơ sở'} ·{' '}
              {courts.data?.find((c) => c.id === g.booking.courtId)?.name || 'Sân'}
            </h2>
            <p className="mt-1.5 text-sm text-slate-500 dark:text-slate-400">
              {new Date(g.booking.startsAt).toLocaleString('vi-VN', {
                timeZone: g.booking.priceSnapshot.timezone,
              })}{' '}
              →{' '}
              {new Date(g.booking.endsAt).toLocaleTimeString('vi-VN', {
                timeZone: g.booking.priceSnapshot.timezone,
              })}
            </p>
          </div>
          {pending && (
            <Countdown
              expiresAt={g.deadline}
              className="inline-flex items-center rounded-full border border-amber-200 px-4 py-1.5 text-sm font-bold"
            />
          )}
        </div>

        <div className="mt-5 rounded-2xl border border-slate-100 bg-slate-50/80 p-4 dark:border-slate-800 dark:bg-slate-800/40">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <p className="text-xs font-semibold uppercase tracking-[0.12em] text-slate-500">
              Tiến độ đóng góp
            </p>
            <p className="font-extrabold text-slate-900 dark:text-white">
              Đã đóng <span className="text-emerald-700 dark:text-emerald-400">{money(g.totalPaid)}</span>{' '}
              / {money(total)}
            </p>
          </div>
          <div
            role="progressbar"
            aria-label="Tiến độ thanh toán nhóm"
            aria-valuemin={0}
            aria-valuemax={total}
            aria-valuenow={g.totalPaid}
            className="mt-3 h-2.5 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700"
          >
            <div
              className="h-full rounded-full bg-emerald-500 transition-all duration-500"
              style={{ width: `${total > 0 ? Math.min(100, (g.totalPaid / total) * 100) : 0}%` }}
            />
          </div>
          <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">
            {g.allocationsLocked
              ? 'Danh sách đã chốt; mỗi người thanh toán phần được phân bổ.'
              : 'Chờ thành viên tham gia và người tổ chức chia tiền.'}
          </p>
        </div>
      </section>

      {/* Owner Pending Actions */}
      {owner && pending && !locked && <GroupPaymentSetup group={g} reload={r.reload} />}
      {/* Errors */}
      {(error || r.error) && (
        <p
          role="alert"
          className="rounded-2xl border border-red-100 bg-red-50 p-4 text-sm text-red-700"
        >
          {error || r.error}
        </p>
      )}

      {pending && (
        <p role="note" className={amberNotice}>
          <strong>Đóng đủ trước hạn:</strong> nếu đến hạn góp tiền mà nhóm chưa đủ 100% tiền sân, nhóm
          mất lượt đặt và <strong>toàn bộ tiền đã đóng không được hoàn lại</strong>.
        </p>
      )}
      {/* Payment Tracking */}
      <RefundPolicyNotice />
      <GroupPaymentTracking
        group={g}
        userId={user?.id}
        busy={busy}
        pay={pay}
        reload={() => {
          r.reload()
          if (claimPath) claims.reload()
        }}
        claims={claims.data ?? []}
      />

      {/* Member leave button */}
      {!owner && pending && (
        <Button
          className="rounded-full"
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
          className={`${primaryPill} flex h-12 w-full`}
          href={`/customer/bookings/${id}`}
        >
          Mở lượt đặt và mã xác nhận vào sân
        </Link>
      )}

      {/* Expired state notice with prominent Delete Action */}
      {g.state === 'GROUP_EXPIRED' && (
        <div className={`${amberNotice} flex flex-col items-start justify-between gap-4 sm:flex-row sm:items-center`}>
          <p>
            Nhóm không đóng đủ tiền trước hạn nên đã mất lượt đặt và sân được giải phóng. Khoản đã đóng{' '}
            {g.totalPaid > 0 && <strong>{money(g.totalPaid)} </strong>}không được hoàn lại.
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
    </>
  )
}
