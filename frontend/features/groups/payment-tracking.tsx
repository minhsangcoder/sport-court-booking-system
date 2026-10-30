'use client'
import { Button } from '@/components/ui/button'
import { CourtLines } from '@/features/home/home-art'
import { money } from '@/features/schedule/types'
import { amberNotice, card, emeraldNotice, outlinePill, primaryPill } from '@/features/transfers/transfer-styles'
import { api } from '@/lib/api'
import { useRef, useState } from 'react'
import { type ContributionClaim, type Group, type ReminderResult, memberStates } from './types'

const reasons: Record<string, string> = {
  PAID: 'Đã đủ tiền hoặc không cần đóng',
  COOLDOWN: 'Vừa được nhắc; vui lòng chờ',
  NOT_MEMBER: 'Không còn trong nhóm',
  SELF: 'Không gửi nhắc cho chính mình',
  WINDOW_CLOSED: 'Đã đóng thời hạn góp tiền',
  NO_ALLOCATION: 'Chưa phân chia chi phí',
}
export function GroupPaymentTracking({
  group: g,
  userId,
  busy,
  pay,
  reload,
  claims = [],
}: {
  group: Group
  userId?: string
  busy: boolean
  pay: (id: string) => void
  reload: () => void
  claims?: ContributionClaim[]
}) {
  const owner = userId === g.ownerId
  const [filter, setFilter] = useState('ALL')
  const [selected, setSelected] = useState<string[]>([])
  const [confirmation, setConfirmation] = useState<{ ids: string[]; all: boolean } | null>(null)
  const [sending, setSending] = useState(false)
  const flight = useRef(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<ReminderResult | null>(null)
  const claimOf = (memberId: string) => claims.find((c) => c.memberId === memberId)
  // A member the organizer is already paying for must not be told to transfer as well.
  const eligible = g.members.filter((m) => m.reminderEligible && !claimOf(m.id)?.paidByCaller)
  const selectedEligible = selected.filter((id) => eligible.some((m) => m.id === id))
  const recipients = confirmation ? g.members.filter((m) => confirmation.ids.includes(m.id)) : []
  const date = (value: string) =>
    new Date(value).toLocaleString('vi-VN', { timeZone: g.booking.priceSnapshot.timezone })
  function confirm(all: boolean) {
    setError('')
    setResult(null)
    setConfirmation({ ids: all ? eligible.map((m) => m.id) : selectedEligible, all })
  }
  async function send() {
    if (!confirmation || flight.current) return
    flight.current = true
    setSending(true)
    setError('')
    setResult(null)
    try {
      const response = await api<ReminderResult>(`/groups/${g.id}/payment-reminders`, {
        method: 'POST',
        body: JSON.stringify(
          confirmation.all && !claims.some((c) => c.paidByCaller)
            ? { remindAll: true }
            : { memberIds: confirmation.ids },
        ),
      })
      setResult(response)
      setSelected([])
      setConfirmation(null)
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể gửi lời nhắc. Vui lòng thử lại.')
      reload()
    } finally {
      flight.current = false
      setSending(false)
    }
  }
  const shown = g.members.filter(
    (m) =>
      filter === 'ALL' ||
      (filter === 'PAID'
        ? m.amountPaid >= m.amountDue && g.allocationsLocked
        : m.amountPaid < m.amountDue),
  )
  return (
    <section className="home-rise min-w-0 [animation-delay:120ms]" aria-label="Theo dõi thanh toán nhóm">
      {owner &&
        g.state === 'GROUP_PENDING' &&
        g.allocationsLocked &&
        g.totalPaid < g.booking.amount && (
          <div className={`${emeraldNotice} mb-5 font-normal`}>
            <h2 className="font-bold">Thanh toán phần còn lại để nhận sân</h2>
            <p className="mt-2 leading-relaxed">
              Còn thiếu {money(g.booking.amount - g.totalPaid, g.booking.currency)}. Bạn có thể đóng
              phần của mình và bấm “Thanh toán thay” cho từng thành viên chưa đóng bên dưới, trước
              hạn góp tiền.
            </p>
            <p className="mt-2 text-xs leading-relaxed opacity-80">
              Khi bạn bấm “Thanh toán thay”, QR của thành viên đó bị vô hiệu ngay và họ không tạo
              được QR mới. Thành viên đã chuyển một phần tiền thì phải hoàn tất phần của mình.
            </p>
          </div>
        )}
      <div className={`${card} p-5 sm:p-6`}>
        <CourtLines className="pointer-events-none absolute -right-20 -top-12 -z-10 w-80 rotate-[-6deg] text-emerald-600/[0.04]" />
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-xl font-extrabold tracking-tight text-slate-950 dark:text-white">
            Thành viên · {g.members.length}/{g.maxMembers}
          </h2>
          <Button variant="outline" disabled={sending} onClick={reload} className={outlinePill}>
            Tải lại thanh toán
          </Button>
        </div>
        <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">
          Còn thiếu {money(Math.max(0, g.booking.amount - g.totalPaid), g.booking.currency)} · Hạn
          góp tiền: {date(g.deadline)}
        </p>
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
          <label className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-300">
            Tình trạng thanh toán
            <select
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="h-10 max-w-full rounded-full border border-slate-200 bg-white px-4 text-sm font-semibold text-slate-700 outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"
            >
              <option value="ALL">Tất cả</option>
              <option value="UNPAID">Chưa đủ tiền</option>
              <option value="PAID">Đã đủ tiền</option>
            </select>
          </label>
          {owner && g.state === 'GROUP_PENDING' && g.allocationsLocked && (
            <div className="flex flex-wrap gap-2.5">
              <Button
                variant="outline"
                disabled={busy || sending || selectedEligible.length === 0}
                onClick={() => confirm(false)}
                className={outlinePill}
              >
                Nhắc thanh toán ({selectedEligible.length})
              </Button>
              <Button
                variant="outline"
                disabled={busy || sending || eligible.length === 0}
                onClick={() => confirm(true)}
                className={outlinePill}
              >
                Nhắc tất cả chưa thanh toán
              </Button>
            </div>
          )}
        </div>
        {error && (
          <p role="alert" className="mt-4 rounded-2xl bg-red-50 p-4 text-sm text-red-700">
            {error} {confirmation && 'Bạn có thể bấm xác nhận để thử lại.'}
          </p>
        )}
        {result && (
          <div role="status" className={`${emeraldNotice} mt-4 font-normal`}>
            <p>
              Đã nhận {result.acceptedCount} lời nhắc. Thành viên nhận qua chuông thông báo trong ứng
              dụng và email/SĐT đã xác minh. Bỏ qua {result.skippedCount} thành viên.
            </p>
            {result.members
              .filter((m) => m.outcome === 'SKIPPED')
              .map((m) => (
                <p className="mt-2 break-words" key={m.memberId}>
                  {g.members.find((x) => x.id === m.memberId)?.displayName || 'Thành viên'}:{' '}
                  {reasons[m.reason || ''] || 'Không còn cần nhắc'}
                  {m.reason === 'COOLDOWN' &&
                    m.nextReminderAt &&
                    ` · Có thể nhắc lại từ ${date(m.nextReminderAt)}`}
                </p>
              ))}
          </div>
        )}
        {confirmation && (
          <div className={`${amberNotice} mt-4`} role="region" aria-label="Xác nhận nhắc thanh toán">
            <h3 className="font-bold">Xác nhận gửi nhắc thanh toán</h3>
            <p className="mt-2">
              Hạn góp tiền: {date(g.deadline)}. Hệ thống kiểm tra lại số tiền và trạng thái trước khi
              nhận lời nhắc.
            </p>
            <ul className="mt-3 space-y-2">
              {recipients.map((m) => (
                <li className="break-words" key={m.id}>
                  {m.displayName} · Còn{' '}
                  {money(Math.max(0, m.amountDue - m.amountPaid), g.booking.currency)}
                </li>
              ))}
            </ul>
            {confirmation.all && (
              <p className="mt-3 text-xs">
                Danh sách tất cả sẽ được xác định lại trên máy chủ khi gửi.
              </p>
            )}
            <div className="mt-4 flex flex-wrap gap-2.5">
              <Button className={primaryPill} disabled={sending || busy} onClick={send}>
                {sending ? 'Đang gửi…' : 'Xác nhận gửi nhắc'}
              </Button>
              <Button
                variant="outline"
                disabled={sending}
                onClick={() => setConfirmation(null)}
                className={outlinePill}
              >
                Hủy gửi nhắc
              </Button>
            </div>
          </div>
        )}
        <div className="mt-5 grid gap-3">
          {shown.map((m) => {
            const claim = claimOf(m.id)
            const payable =
              g.state === 'GROUP_PENDING' &&
              g.allocationsLocked &&
              m.amountDue > 0 &&
              m.amountPaid === 0
            const self = m.userId === userId
            const paid = g.allocationsLocked && m.amountDue > 0 && m.amountPaid >= m.amountDue
            const organizerPaying = payable && self && !owner && claim && !claim.paidByCaller
            const memberPaying = payable && owner && !self && claim && !claim.paidByCaller
            return (
              <div
                className="flex min-w-0 flex-wrap items-start justify-between gap-4 rounded-2xl border border-slate-200/80 bg-slate-50/60 p-4 transition hover:border-emerald-600/30 dark:border-slate-800 dark:bg-slate-800/40"
                key={m.id}
              >
                <div className="min-w-0 flex-1 basis-48">
                  <p className="break-words font-bold text-slate-900 dark:text-white">
                    {m.displayName}{' '}
                    {m.userId === g.ownerId && (
                      <span className="ml-1 rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] font-bold text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300">
                        Người tổ chức
                      </span>
                    )}
                  </p>
                  <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                    <span className={paid ? 'font-semibold text-emerald-700 dark:text-emerald-400' : ''}>
                      {g.allocationsLocked && m.amountDue === 0
                        ? 'Không cần đóng'
                        : memberStates[m.paymentState]}
                    </span>{' '}
                    · {money(m.amountPaid, g.booking.currency)} /{' '}
                    {money(m.amountDue, g.booking.currency)}
                  </p>
                  <p className="mt-1 text-sm font-semibold text-slate-800 dark:text-slate-200">
                    Còn {money(Math.max(0, m.amountDue - m.amountPaid), g.booking.currency)}
                  </p>
                  {organizerPaying && (
                    <p role="status" className={`${amberNotice} mt-3 p-3 text-xs`}>
                      Người tổ chức đang thanh toán thay phần này. Bạn không cần chuyển khoản; nếu còn
                      giữ QR cũ, đừng dùng lại.
                    </p>
                  )}
                  {memberPaying && (
                    <p className="mt-2 text-xs text-amber-800 dark:text-amber-300">
                      Thành viên đang mở QR. “Thanh toán thay” sẽ vô hiệu QR đó; hãy báo họ dừng chuyển
                      khoản.
                    </p>
                  )}
                  {m.lastReminderAt && (
                    <p className="mt-2 text-xs text-slate-500">
                      Nhắc gần nhất (đã nhận vào hàng đợi): {date(m.lastReminderAt)}
                    </p>
                  )}
                  {owner && m.reminderBlockedReason === 'COOLDOWN' && m.nextReminderAt && (
                    <p className="mt-2 text-xs text-amber-800 dark:text-amber-300">
                      Có thể nhắc lại từ {date(m.nextReminderAt)}
                    </p>
                  )}
                  {owner && m.userId !== g.ownerId && m.reminderBlockedReason === 'WINDOW_CLOSED' && (
                    <p className="mt-2 text-xs text-slate-500">Đã đóng thời hạn góp tiền</p>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-3">
                  {owner &&
                    m.userId !== g.ownerId &&
                    g.state === 'GROUP_PENDING' &&
                    g.allocationsLocked && (
                      <label className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
                        <input
                          type="checkbox"
                          className="size-4 accent-emerald-600"
                          aria-label={`Chọn nhắc ${m.displayName}`}
                          checked={selectedEligible.includes(m.id)}
                          disabled={!eligible.includes(m) || sending || busy}
                          onChange={(e) =>
                            setSelected((ids) =>
                              e.target.checked ? [...ids, m.id] : ids.filter((id) => id !== m.id),
                            )
                          }
                        />
                        Chọn nhắc
                      </label>
                    )}
                  {payable && (owner || self) && !organizerPaying && (
                    <Button disabled={busy || sending} onClick={() => pay(m.id)} className={primaryPill}>
                      {self
                        ? 'Đóng phần của tôi'
                        : claim?.paidByCaller
                          ? 'Mở lại QR thanh toán thay'
                          : 'Thanh toán thay'}
                    </Button>
                  )}
                </div>
              </div>
            )
          })}
          {shown.length === 0 && (
            <p className="p-5 text-center text-sm text-slate-500">Không có thành viên phù hợp.</p>
          )}
        </div>
      </div>
    </section>
  )
}
