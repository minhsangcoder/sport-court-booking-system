'use client'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { RecordDetails } from '@/components/record-details'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'
import { businessApi } from '@/lib/business-api'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
import type { AdminAccount } from './admin-account-types'
import { AdminPage } from './admin-nav'
type AccountDetail = {
  account: AdminAccount
  sessions: {
    id: string
    deviceInfo: string
    ipAddress: string
    createdAt: string
    expiresAt: string
    revoked: boolean
  }[]
  audit: {
    id: string
    actorId: string
    action: string
    oldValue: unknown
    newValue: unknown
    createdAt: string
  }[]
}
export function AdminAccountDetail({ id }: { id: string }) {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Loaded id={id} />
    </RequireAuth>
  )
}
function Loaded({ id }: { id: string }) {
  const { user } = useAuth()
  const r = useApi<AccountDetail>(`/admin/accounts/${id}`)
  const [tab, setTab] = useState('audit')
  const [action, setAction] = useState<'lock' | 'unlock' | 'roles' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!action || busy) return
    const f = new FormData(e.currentTarget)
    setBusy(true)
    setError('')
    setSuccess('')
    try {
      const body =
        action === 'roles'
          ? { reason: f.get('reason'), roles: f.getAll('roles') }
          : { reason: f.get('reason'), duration: f.get('duration') }
      await api(`/admin/accounts/${id}/${action}`, {
        method: action === 'roles' ? 'PUT' : 'POST',
        body: JSON.stringify(body),
      })
      setAction(null)
      r.reload()
      setSuccess('Đã cập nhật tài khoản. Các phiên liên quan đã được thu hồi.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể cập nhật tài khoản.')
    } finally {
      setBusy(false)
    }
  }
  if (r.loading)
    return (
      <p role="status" className="p-8">
        Đang tải hồ sơ tài khoản…
      </p>
    )
  if (!r.data)
    return (
      <p role="alert" className="p-8 text-red-700">
        {r.error}
        <button onClick={r.reload} className="ml-4 underline">
          Thử lại
        </button>
      </p>
    )
  const u = r.data.account
  const protectedAdmin = u.roles.includes('ADMIN') || u.id === user?.id
  return (
    <AdminPage
      active="accounts"
      eyebrow={
        <Link className={bannerLink} href="/admin/accounts">
          Tài khoản / {u.fullName}
        </Link>
      }
      title={u.fullName ?? ''}
      width="max-w-5xl"
    >
      <section className="mt-6 grid gap-5 p-6 sm:grid-cols-2 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
        <div>
          <p className="text-sm text-slate-500">Liên hệ</p>
          <p className="mt-2">
            {u.email || 'Chưa có thư điện tử'} {u.emailVerified && '· Đã xác minh'}
          </p>
          <p className="mt-2">
            {u.phone || 'Chưa có số điện thoại'} {u.phoneVerified && '· Đã xác minh'}
          </p>
        </div>
        <div>
          <p className="font-semibold text-emerald-700">
            {displayLabel(u.status)} · {u.roles.map((role) => displayLabel(role)).join(', ')}
          </p>
          <p className="mt-2 text-sm text-slate-500">
            Đăng ký: {new Date(u.createdAt).toLocaleString('vi-VN')}
          </p>
          {u.lockReason && (
            <p className="mt-2 text-sm text-red-700">
              Lý do khóa: {u.lockReason} ·{' '}
              {u.lockedUntil ? new Date(u.lockedUntil).toLocaleString('vi-VN') : 'Vĩnh viễn'}
            </p>
          )}
        </div>
      </section>
      <div className="mt-5 flex flex-wrap gap-3">
        {!protectedAdmin &&
          ['suspend', 'deactivate', 'reactivate']
            .filter((op) => op !== 'reactivate' || ['SUSPENDED', 'DEACTIVATED'].includes(u.status))
            .map((op) => (
              <Button
                key={op}
                variant="outline"
                disabled={busy}
                onClick={async () => {
                  const reason = window.prompt(
                    'Lý do thay đổi trạng thái tài khoản (tối đa 500 ký tự)',
                  )
                  if (!reason?.trim()) return
                  setBusy(true)
                  setError('')
                  try {
                    await businessApi.accountState(
                      id,
                      op as 'suspend' | 'deactivate' | 'reactivate',
                      reason,
                    )
                    r.reload()
                  } catch (e) {
                    setError(e instanceof Error ? e.message : 'Không thể thay đổi trạng thái')
                  } finally {
                    setBusy(false)
                  }
                }}
              >
                {op === 'suspend'
                  ? 'Tạm ngừng tài khoản'
                  : op === 'deactivate'
                    ? 'Vô hiệu hóa tài khoản'
                    : 'Kích hoạt lại tài khoản'}
              </Button>
            ))}
        {!protectedAdmin && (
          <>
            <Button
              variant="outline"
              onClick={() => {
                setAction(u.status === 'LOCKED' ? 'unlock' : 'lock')
                setError('')
              }}
            >
              {u.status === 'LOCKED' ? 'Mở khóa tài khoản' : 'Khóa tài khoản'}
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                setAction('roles')
                setError('')
              }}
            >
              Cập nhật vai trò
            </Button>
          </>
        )}
        {protectedAdmin && (
          <p className="text-sm text-amber-800">
            Thao tác giữa các tài khoản quản trị viên đang chờ chốt chính sách phân cấp.
          </p>
        )}
      </div>
      {error && !action && (
        <p role="alert" className="mt-4 text-red-700">
          {error}
        </p>
      )}
      {success && (
        <p role="status" className="mt-5 text-emerald-700">
          {success}
        </p>
      )}
      {action && (
        <form onSubmit={submit} className="mt-6 space-y-4 rounded-2xl bg-slate-50 p-6">
          <h2 className="text-lg font-extrabold tracking-tight">
            {action === 'lock'
              ? 'Khóa tài khoản và thu hồi mọi phiên'
              : action === 'unlock'
                ? 'Mở khóa tài khoản'
                : 'Cập nhật vai trò và thu hồi mọi phiên'}
          </h2>
          {action === 'lock' && (
            <label className="grid gap-2 text-sm">
              Thời hạn
              <select name="duration" className="h-11 rounded-xl border bg-white px-3.5">
                <option value="SEVEN_DAYS">7 ngày</option>
                <option value="THIRTY_DAYS">30 ngày</option>
                <option value="PERMANENT">Vĩnh viễn</option>
              </select>
            </label>
          )}
          {action === 'roles' && (
            <fieldset>
              <legend className="mb-3 text-sm">Vai trò cho tài khoản</legend>
              <div className="flex flex-wrap gap-4">
                {['CUSTOMER', 'OWNER', 'STAFF'].map((role) => (
                  <label className="flex items-center gap-2 text-sm" key={role}>
                    <input
                      type="checkbox"
                      name="roles"
                      value={role}
                      defaultChecked={u.roles.includes(role)}
                      disabled={busy}
                    />
                    {displayLabel(role)}
                  </label>
                ))}
              </div>
            </fieldset>
          )}
          <label className="grid gap-2 text-sm">
            Lý do xử lý
            <Input name="reason" minLength={2} maxLength={500} required disabled={busy} />
          </label>
          {error && (
            <p role="alert" className="text-red-700">
              {error}
            </p>
          )}
          <div className="flex gap-3">
            <Button type="submit" className="bg-emerald-600 text-white" disabled={busy}>
              Xác nhận cập nhật
            </Button>
            <Button variant="outline" onClick={() => setAction(null)} disabled={busy}>
              Đóng
            </Button>
          </div>
        </form>
      )}
      <div role="tablist" aria-label="Nhật ký tài khoản" className="mt-8 flex gap-3 border-b pb-4">
        {[
          ['audit', 'Lịch sử hoạt động'],
          ['sessions', 'Phiên đăng nhập'],
        ].map(([value, label]) => (
          <button
            role="tab"
            aria-selected={tab === value}
            key={value}
            onClick={() => setTab(value)}
            className={`rounded-full px-4 py-2 text-sm ${tab === value ? 'bg-emerald-600 text-white' : 'bg-slate-100'}`}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="mt-5 space-y-4">
        {tab === 'audit'
          ? r.data.audit.map((a) => (
              <article
                key={a.id}
                className="p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
              >
                <p className="text-sm font-bold">{displayLabel(a.action)}</p>
                <p className="mt-2 text-xs text-slate-500">
                  {new Date(a.createdAt).toLocaleString('vi-VN')}
                </p>
                {a.newValue != null && (
                  <details className="mt-3 text-sm">
                    <summary>Nội dung thay đổi</summary>
                    <div className="mt-3 overflow-x-auto rounded-2xl bg-slate-50 p-3 text-xs">
                      <RecordDetails value={a.newValue} />
                    </div>
                  </details>
                )}
              </article>
            ))
          : r.data.sessions.map((s) => (
              <article
                key={s.id}
                className="p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
              >
                <p className="text-sm font-bold">
                  {s.revoked ? 'Đã thu hồi' : 'Chưa thu hồi'} ·{' '}
                  {s.deviceInfo || 'Thiết bị chưa xác định'}
                </p>
                <p className="mt-2 text-xs text-slate-500">
                  IP {s.ipAddress} · Tạo {new Date(s.createdAt).toLocaleString('vi-VN')} · Hết hạn{' '}
                  {new Date(s.expiresAt).toLocaleString('vi-VN')}
                </p>
              </article>
            ))}
      </div>
    </AdminPage>
  )
}
