'use client'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { BannerPage } from '@/components/page-banner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import { money } from '@/features/schedule/types'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import type { Group } from './types'
export function SplitPage({ id }: { id: string }) {
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
  const [mode, setMode] = useState('EQUAL')
  const [amounts, setAmounts] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!r.data) return
    setBusy(true)
    setError('')
    try {
      await api(`/groups/${id}/allocations`, {
        method: 'PUT',
        body: JSON.stringify({
          mode,
          allocations:
            mode === 'CUSTOM'
              ? r.data.members.map((m) => ({
                  memberId: m.id,
                  amount: Number(amounts[m.id] ?? m.amountDue),
                }))
              : null,
        }),
      })
      router.push(`/customer/groups/${id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể chia tiền.')
    } finally {
      setBusy(false)
    }
  }
  if (r.loading)
    return (
      <p className="p-8" role="status">
        Đang tải phân chia…
      </p>
    )
  if (!r.data)
    return (
      <p className="p-8 text-red-700" role="alert">
        {r.error}
      </p>
    )
  const g = r.data
  const sum = g.members.reduce((v, m) => v + Number(amounts[m.id] ?? m.amountDue), 0)
  const base = Math.floor(g.booking.amount / g.members.length)
  const remainder = g.booking.amount - base * g.members.length
  const locked =
    g.members.some((m) => m.amountPaid > 0 || Boolean(m.paymentRequestedAt)) ||
    g.state !== 'GROUP_PENDING'
  if (g.ownerId !== user?.id)
    return (
      <div className="mx-auto max-w-2xl px-4 py-9 sm:px-6">
        <p
          role="alert"
          className="rounded-2xl border border-amber-200/80 bg-amber-50/70 p-4 text-sm text-amber-950/85 dark:border-amber-500/20 dark:bg-amber-500/5 dark:text-amber-100/85"
        >
          Chỉ người tổ chức được chia tiền nhóm.
        </p>
      </div>
    )
  return (
    <BannerPage
      eyebrow={
        <Link className={bannerLink} href={`/customer/groups/${id}`}>
          {g.name} / Phân chia chi phí
        </Link>
      }
      title="Chốt danh sách và chia tiền"
      width="max-w-2xl"
    >
      <p className="mt-3 text-slate-500">
        Tổng tiền giữ nguyên: {money(g.booking.amount)}. Phần dư chia đều được cộng cho người tổ
        chức.
      </p>
      <form className="mt-6 space-y-5" onSubmit={submit}>
        <label className="grid gap-2 text-sm">
          Cách chia
          <select
            className="rounded-2xl border border-slate-200/90 p-3"
            value={mode}
            onChange={(e) => setMode(e.target.value)}
            disabled={busy || locked}
          >
            <option value="EQUAL">Chia đều</option>
            <option value="CUSTOM">Tùy chỉnh từng người</option>
          </select>
        </label>
        <div className="divide-y rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          {g.members.map((m) => (
            <label key={m.id} className="flex items-center justify-between gap-4 p-5">
              <span>{m.displayName}</span>
              {mode === 'CUSTOM' ? (
                <Input
                  className="w-40"
                  type="number"
                  min={0}
                  step={1}
                  required
                  value={amounts[m.id] ?? m.amountDue}
                  onChange={(e) => setAmounts((v) => ({ ...v, [m.id]: e.target.value }))}
                  disabled={busy || locked}
                />
              ) : (
                <strong>{money(base + (m.userId === g.ownerId ? remainder : 0))}</strong>
              )}
            </label>
          ))}
        </div>
        {mode === 'CUSTOM' && (
          <p className={sum === g.booking.amount ? 'text-emerald-700' : 'text-red-700'}>
            Tổng phân bổ: {money(sum)} · Chênh lệch: {money(sum - g.booking.amount)}
          </p>
        )}
        {(error || locked) && (
          <p role="alert" className="rounded-2xl bg-amber-50 p-4 text-sm text-amber-800">
            {error || 'Không thể thay đổi phân bổ khi thanh toán đã bắt đầu hoặc nhóm đã kết thúc.'}
          </p>
        )}
        <Button
          type="submit"
          className="h-12 w-full bg-emerald-600 text-white"
          disabled={busy || locked || (mode === 'CUSTOM' && sum !== g.booking.amount)}
        >
          Xác nhận phân chia và khóa danh sách
        </Button>
      </form>
    </BannerPage>
  )
}
