'use client'
import Link from 'next/link'
import { useCallback, useEffect, useState } from 'react'
import { api, authApi, ApiError, type UserProfile } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'
import { RequireAuth } from '@/features/auth/require-auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

type Channel = 'EMAIL' | 'PHONE'
type Challenge = { id: string; channel: Channel; recipient: string; expiresAt: string; resendAfter: string; usable: boolean }
const channels: Channel[] = ['EMAIL', 'PHONE']
const labels = { EMAIL: 'Email', PHONE: 'Số điện thoại' }
const mailpit = process.env.NEXT_PUBLIC_DEMO_MAILPIT_URL

export default function ContactPage() {
  const { user, ready } = useAuth()
  const [challenges, setChallenges] = useState<Challenge[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<Channel | null>(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [now, setNow] = useState(0)
  const load = useCallback(async () => {
    setChallenges(await api<Challenge[]>('/users/me/contact-challenges'))
  }, [])
  useEffect(() => {
    if (!ready || !user) return
    let active = true
    void api<Challenge[]>('/users/me/contact-challenges').then(value => { if (active) setChallenges(value) })
      .catch(err => { if (active) setError(err instanceof ApiError ? err.message : 'Không thể tải yêu cầu xác minh.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [ready, user?.id, user])
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  async function perform(channel: Channel, operation: () => Promise<void>) {
    if (busy) return
    setBusy(channel); setError(''); setMessage('')
    try { await operation() }
    catch (err) {
      setError(err instanceof ApiError ? err.message : 'Không thể cập nhật thông tin liên hệ.')
      await load().catch(() => {})
    } finally { setBusy(null) }
  }
  function requestChange(event: React.FormEvent<HTMLFormElement>, channel: Channel) {
    event.preventDefault()
    const recipient = String(new FormData(event.currentTarget).get('recipient') ?? '').trim()
    if (recipient === (channel === 'EMAIL' ? user?.email : user?.phone)) {
      setError('Thông tin mới phải khác thông tin đang sử dụng.'); return
    }
    void perform(channel, async () => {
      await api('/users/me', { method: 'PATCH', body: JSON.stringify({ [channel === 'EMAIL' ? 'email' : 'phone']: recipient }) })
      await load()
      setMessage(`Đã gửi mã xác minh đến ${recipient}. Thông tin hiện tại vẫn được sử dụng đến khi xác minh thành công.`)
    })
  }
  function verify(event: React.FormEvent<HTMLFormElement>, challenge: Challenge) {
    event.preventDefault()
    const code = String(new FormData(event.currentTarget).get('code') ?? '')
    void perform(challenge.channel, async () => {
      await authApi.verify({ challengeId: challenge.id, code })
      const updated = await api<UserProfile>('/users/me')
      window.dispatchEvent(new CustomEvent('sporthub-session', { detail: updated }))
      await load()
      setMessage(`${labels[challenge.channel]} đã được xác minh và cập nhật.`)
    })
  }
  function resend(challenge: Challenge) {
    void perform(challenge.channel, async () => {
      await api(`/users/me/contact-challenges/${challenge.channel}/resend`, { method: 'POST' })
      await load(); setMessage('Đã gửi mã mới. Hãy dùng mã trong thông báo mới nhất.')
    })
  }
  return <RequireAuth><main className="mx-auto max-w-2xl px-5 py-10">
    <Link href="/customer/profile" className="text-sm font-medium text-emerald-700">← Hồ sơ của tôi</Link>
    <h1 className="mt-5 text-3xl font-bold">Email và số điện thoại</h1>
    <p className="mt-2 text-slate-500">Xác minh thông tin mới trước khi sử dụng để đăng nhập và nhận thông báo.</p>
    {loading && <p role="status" className="mt-6">Đang tải yêu cầu xác minh…</p>}
    {error && <div role="alert" className="mt-5 rounded-xl bg-red-50 p-4 text-red-700">{error}<Button variant="ghost" className="ml-2" disabled={!!busy} onClick={() => { setLoading(true); void load().then(() => setError('')).catch(err => setError(err instanceof ApiError ? err.message : 'Không thể tải lại.')).finally(() => setLoading(false)) }}>Tải lại</Button></div>}
    {message && <p role="status" className="mt-5 rounded-xl bg-emerald-50 p-4 text-emerald-800">{message}</p>}
    {!loading && channels.map(channel => {
      const challenge = challenges.find(item => item.channel === channel)
      const current = channel === 'EMAIL' ? user?.email : user?.phone
      const verified = channel === 'EMAIL' ? user?.emailVerified : user?.phoneVerified
      const wait = challenge ? (now === 0 ? 60 : Math.max(0, Math.ceil((Date.parse(challenge.resendAfter) - now) / 1000))) : 0
      const expired = challenge && (!challenge.usable || (now > 0 && Date.parse(challenge.expiresAt) <= now))
      return <section key={channel} className="mt-6 rounded-2xl border bg-white p-6">
        <h2 className="text-xl font-semibold">{labels[channel]}</h2>
        <p className="mt-2 break-all text-sm text-slate-600">Đang sử dụng: {current ?? 'Chưa cung cấp'} {current && <span className={verified ? 'text-emerald-700' : 'text-amber-700'}>· {verified ? 'Đã xác minh' : 'Chưa xác minh'}</span>}</p>
        <form onSubmit={event => requestChange(event, channel)} className="mt-5 grid gap-3">
          <label className="grid gap-2 text-sm">{labels[channel]} mới<Input name="recipient" type={channel === 'EMAIL' ? 'email' : 'tel'} placeholder={channel === 'EMAIL' ? 'ban@example.com' : '+84901234567'} maxLength={channel === 'EMAIL' ? 254 : 16} pattern={channel === 'PHONE' ? '\\+[1-9][0-9]{7,14}' : undefined} required disabled={!!busy} /></label>
          <Button type="submit" variant="outline" disabled={!!busy}>{busy === channel ? 'Đang xử lý…' : 'Gửi mã xác minh'}</Button>
        </form>
        {challenge && <div className="mt-5 border-t pt-5">
          <p className="break-all text-sm font-medium">Chờ xác minh: {challenge.recipient}</p>
          <p className="mt-1 text-sm text-slate-500">Mã có hiệu lực đến {new Date(challenge.expiresAt).toLocaleTimeString('vi-VN')}.</p>
          {expired ? <p className="mt-3 text-sm text-amber-700">Mã đã hết hiệu lực. Gửi lại mã để tiếp tục.</p> : <form key={challenge.id} onSubmit={event => verify(event, challenge)} className="mt-3 flex flex-wrap gap-3">
            <label className="grid flex-1 gap-2 text-sm">Mã xác minh<Input name="code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" minLength={6} maxLength={6} required disabled={!!busy} /></label>
            <Button type="submit" className="self-end bg-emerald-600 text-white" disabled={!!busy}>Xác minh</Button>
          </form>}
          <Button variant="ghost" className="mt-3" disabled={!!busy || wait > 0} onClick={() => resend(challenge)}>{wait > 0 ? `Gửi lại sau ${wait}s` : 'Gửi lại mã'}</Button>
        </div>}
      </section>
    })}
    {mailpit && <a href={mailpit} target="_blank" rel="noreferrer" className="mt-6 inline-block text-sm text-emerald-700 hover:underline">Mở hộp thư demo để xem email và SMS demo</a>}
  </main></RequireAuth>
}
