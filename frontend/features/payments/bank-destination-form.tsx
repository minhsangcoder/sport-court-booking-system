'use client'
import { useEffect, useRef, useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useAuth } from '@/lib/auth-context'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { BankSelect } from './bank-select'
import type { BankDestination } from './types'
import { BankProfileStatus, type BankProfile } from './bank-profile-status'

export function BankDestinationForm(props: {
  value: string
  onChange: (revision: string) => void
  guestBookingId?: string
  disabled?: boolean
}) {
  const { user } = useAuth()
  return <BankForm key={`${user?.id}:${props.guestBookingId ?? 'self'}`} {...props} />
}
function BankForm({
  value,
  onChange,
  guestBookingId,
  disabled,
}: {
  value: string
  onChange: (revision: string) => void
  guestBookingId?: string
  disabled?: boolean
}) {
  const profile = useApi<BankProfile>(guestBookingId ? null : '/payments/bank-profile')
  const intent = useRef<{ payload: string; key: string; consent: string; version?: number } | null>(
    null,
  )
  const submitting = useRef(false)
  const canAdd = Boolean(
    guestBookingId ||
    (!profile.loading && (profile.data?.canChange || profile.data?.canRegisterFirst)),
  )
  const [created, setCreated] = useState<BankDestination | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const activeRevision = profile.data?.activeRevisionId
  useEffect(() => {
    if (!guestBookingId && !profile.loading && activeRevision && value !== activeRevision)
      onChange(activeRevision)
  }, [guestBookingId, profile.loading, activeRevision, value, onChange])
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current || busy || disabled || !canAdd) return
    submitting.current = true
    const form = event.currentTarget
    const data = new FormData(form)
    setBusy(true)
    setError('')
    try {
      const fields = {
        bankBin: data.get('bankBin'),
        accountNumber: data.get('accountNumber'),
        accountName: data.get('accountName'),
      }
      const payload = JSON.stringify(fields)
      if (intent.current?.payload !== payload)
        intent.current = {
          payload,
          key: crypto.randomUUID(),
          consent: crypto.randomUUID(),
          version: profile.data?.version,
        }
      const result = await api<BankDestination>(
        guestBookingId
          ? `/payments/bookings/${guestBookingId}/guest-bank-destinations`
          : '/payments/bank-destinations',
        {
          method: 'POST',
          headers: { 'Idempotency-Key': intent.current.key },
          body: JSON.stringify({
            ...fields,
            ...(!guestBookingId ? { expectedProfileVersion: intent.current.version } : {}),
            consentReference: intent.current.consent,
          }),
        },
      )
      setCreated(result)
      onChange(result.id)
      form.reset()
      void profile.reload()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Chưa lưu được tài khoản nhận tiền.')
      void profile.reload()
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }
  const destinations = guestBookingId
    ? created
      ? [created]
      : []
    : profile.data?.destination
      ? [profile.data.destination]
      : []
  return (
    <section className="my-4 space-y-3 rounded-3xl border border-slate-200/90 bg-white p-5 text-slate-900 shadow-xl shadow-slate-900/5 sm:p-6 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100">
      <h3 className="font-semibold">
        Tài khoản nhận tiền {guestBookingId ? 'của khách vãng lai' : 'của bạn'}
      </h3>
      {!guestBookingId &&
        (profile.data && !profile.loading ? (
          <BankProfileStatus profile={profile.data} />
        ) : (
          <p role={profile.error ? 'alert' : 'status'}>
            {profile.error || 'Đang kiểm tra quyền thay đổi ngân hàng…'}{' '}
            <button type="button" className="underline" onClick={profile.reload}>
              Tải lại
            </button>
          </p>
        ))}
      {destinations.length > 0 && (
        <label className="grid gap-2 text-sm">
          Chọn tài khoản đã khai
          <select
            value={value}
            onChange={(event) => onChange(event.target.value)}
            disabled={
              busy ||
              disabled ||
              (!guestBookingId && (!profile.data || profile.loading || !profile.data.canChange))
            }
            className="h-11 rounded-lg border bg-white px-3"
          >
            <option value="">Chọn tài khoản nhận hiện tại</option>
            {destinations.map((item) => (
              <option key={item.id} value={item.id}>
                {item.bankName} · {item.maskedAccountNumber} · {item.accountName}
              </option>
            ))}
          </select>
        </label>
      )}
      <details open={destinations.length === 0}>
        <summary className="cursor-pointer text-sm font-semibold">Khai tài khoản nhận mới</summary>
        <form onSubmit={save} className="mt-3 space-y-3">
          <BankSelect disabled={busy || disabled || !canAdd} />
          <label className="grid gap-2 text-sm">
            Số tài khoản
            <Input
              name="accountNumber"
              type="text"
              inputMode="numeric"
              pattern="[0-9]{1,19}"
              minLength={1}
              maxLength={19}
              autoComplete="off"
              required
              disabled={busy || disabled || !canAdd}
            />
          </label>
          <label className="grid gap-2 text-sm">
            Tên người nhận (tự khai)
            <Input
              name="accountName"
              maxLength={100}
              autoComplete="off"
              disabled={busy || disabled || !canAdd}
            />
          </label>
          <label className="flex gap-2 text-sm">
            <input type="checkbox" required disabled={busy || disabled || !canAdd} />
            Tôi đã kiểm tra ngân hàng, số tài khoản và đồng ý dùng thông tin này nhận tiền.
          </label>
          <p className="text-xs text-slate-600">
            Thông tin do người nhận cung cấp; SportHub chưa xác minh tên chủ tài khoản. Thay tài
            khoản mới không tự đổi khoản đã chốt.
          </p>
          <Button type="submit" variant="outline" disabled={busy || disabled || !canAdd}>
            {busy ? 'Đang lưu…' : 'Lưu tài khoản nhận'}
          </Button>
        </form>
      </details>
      {error && (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      )}
    </section>
  )
}
