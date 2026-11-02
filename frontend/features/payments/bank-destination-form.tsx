'use client'
import { useEffect, useRef, useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useAuth } from '@/lib/auth-context'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import {
  card,
  emeraldNotice,
  fieldLabel,
  outlinePill,
  primaryPill,
  selectField,
} from '@/features/transfers/transfer-styles'
import { Pencil } from 'lucide-react'
import { BankSelect } from './bank-select'
import type { BankDestination } from './types'
import { BankProfileStatus, type BankProfile } from './bank-profile-status'

export function BankDestinationForm(props: {
  value: string
  onChange: (revision: string) => void
  guestBookingId?: string
  disabled?: boolean
  /** Show the saved account read-only with an edit button, for the profile page. */
  summary?: boolean
}) {
  const { user } = useAuth()
  return <BankForm key={`${user?.id}:${props.guestBookingId ?? 'self'}`} {...props} />
}
function BankForm({
  value,
  onChange,
  guestBookingId,
  disabled,
  summary = false,
}: {
  value: string
  onChange: (revision: string) => void
  guestBookingId?: string
  disabled?: boolean
  summary?: boolean
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
  const [editing, setEditing] = useState(false)
  const [saved, setSaved] = useState(false)
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
    setSaved(false)
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
      setEditing(false)
      setSaved(true)
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
  const current = destinations[0]
  const accountForm = (
    <form
      onSubmit={save}
      className="mt-4 space-y-4 rounded-2xl border border-slate-200/80 bg-slate-50/70 p-4 dark:border-slate-800 dark:bg-slate-800/40"
    >
      <BankSelect
        defaultValue={summary ? current?.bankBin : undefined}
        disabled={busy || disabled || !canAdd}
      />
      <label className={fieldLabel}>
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
      <label className={fieldLabel}>
        Tên người nhận (tự khai)
        <Input
          name="accountName"
          defaultValue={summary ? current?.accountName : undefined}
          maxLength={100}
          autoComplete="off"
          disabled={busy || disabled || !canAdd}
        />
      </label>
      <label className="flex gap-2 text-sm text-slate-700 dark:text-slate-300">
        <input
          type="checkbox"
          required
          disabled={busy || disabled || !canAdd}
          className="mt-0.5 size-4 accent-emerald-600"
        />
        Tôi đã kiểm tra ngân hàng, số tài khoản và đồng ý dùng thông tin này nhận tiền.
      </label>
      <p className="text-xs text-slate-600 dark:text-slate-400">
        Thông tin do người nhận cung cấp; SportHub chưa xác minh tên chủ tài khoản. Thay tài khoản
        mới không tự đổi khoản đã chốt.
      </p>
      <div className="flex flex-wrap gap-2.5">
        <Button
          type="submit"
          disabled={busy || disabled || !canAdd}
          className={summary ? primaryPill : outlinePill}
        >
          {busy ? 'Đang lưu…' : 'Lưu tài khoản nhận'}
        </Button>
        {summary && current && (
          <button
            type="button"
            className={outlinePill}
            disabled={busy}
            onClick={() => {
              setEditing(false)
              setError('')
            }}
          >
            Hủy
          </button>
        )}
      </div>
    </form>
  )
  return (
    <section
      className={`${card} home-rise space-y-4 p-5 text-slate-900 sm:p-6 dark:text-slate-100`}
    >
      <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
        Tài khoản nhận tiền {guestBookingId ? 'của khách vãng lai' : 'của bạn'}
      </h3>
      {summary && (
        <p className="text-sm leading-relaxed text-slate-600 dark:text-slate-400">
          Chỉ cần khai khi bạn muốn đăng chuyển nhượng: người mua chuyển 100% giá đã chốt trực tiếp
          vào tài khoản này qua QR SePay. Đặt sân hoặc mua lại lượt đặt không cần khai.
        </p>
      )}
      {!guestBookingId &&
        (profile.data && !profile.loading ? (
          <BankProfileStatus profile={profile.data} />
        ) : (
          <p
            role={profile.error ? 'alert' : 'status'}
            className="text-sm text-slate-600 dark:text-slate-400"
          >
            {profile.error || 'Đang kiểm tra quyền thay đổi ngân hàng…'}{' '}
            <button type="button" className="underline" onClick={profile.reload}>
              Tải lại
            </button>
          </p>
        ))}
      {saved && (
        <p role="status" className={emeraldNotice}>
          Đã lưu tài khoản nhận tiền.
        </p>
      )}
      {summary ? (
        <>
          {current && !editing && (
            <>
              <dl className="grid gap-3 rounded-2xl bg-slate-50 p-4 text-sm sm:grid-cols-[10rem_1fr] dark:bg-slate-800/50">
                <dt className="text-slate-500 dark:text-slate-400">Ngân hàng</dt>
                <dd className="font-semibold text-slate-900 dark:text-white">{current.bankName}</dd>
                <dt className="text-slate-500 dark:text-slate-400">Số tài khoản</dt>
                <dd className="font-mono font-semibold text-slate-900 dark:text-white">
                  {current.maskedAccountNumber}
                </dd>
                <dt className="text-slate-500 dark:text-slate-400">Tên người nhận</dt>
                <dd className="font-semibold text-slate-900 dark:text-white">
                  {current.accountName}
                </dd>
              </dl>
              <button
                type="button"
                className={outlinePill}
                disabled={!canAdd}
                onClick={() => {
                  setEditing(true)
                  setSaved(false)
                }}
              >
                <Pencil className="size-4" /> Chỉnh sửa
              </button>
            </>
          )}
          {(editing || (!current && !profile.loading && profile.data)) && accountForm}
        </>
      ) : (
        <>
          {destinations.length > 0 && (
            <label className={fieldLabel}>
              Chọn tài khoản đã khai
              <select
                value={value}
                onChange={(event) => onChange(event.target.value)}
                disabled={
                  busy ||
                  disabled ||
                  (!guestBookingId && (!profile.data || profile.loading || !profile.data.canChange))
                }
                className={selectField}
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
            <summary className="cursor-pointer text-sm font-semibold text-emerald-700 dark:text-emerald-400">
              Khai tài khoản nhận mới
            </summary>
            {accountForm}
          </details>
        </>
      )}
      {error && (
        <p role="alert" className="text-sm text-red-700 dark:text-red-300">
          {error}
        </p>
      )}
    </section>
  )
}
