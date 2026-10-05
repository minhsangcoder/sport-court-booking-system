'use client'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import { useState } from 'react'
import { authApi, ApiError } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
type Mode = 'login' | 'register' | 'verify' | 'forgot' | 'reset'
const titles = { login: 'Chào mừng trở lại', register: 'Tạo tài khoản SportHub', verify: 'Xác minh tài khoản', forgot: 'Quên mật khẩu', reset: 'Đặt lại mật khẩu' }
export function AuthForm({ mode }: { mode: Mode }) {
  const router = useRouter(); const params = useSearchParams()
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [success, setSuccess] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy) return
    setBusy(true); setError(''); setSuccess(''); setFieldErrors({})
    const values = new FormData(event.currentTarget)
    const value = (name: string) => String(values.get(name) ?? '').trim()
    try {
      if (mode === 'login') {
        await authApi.login(value('identifier'), String(values.get('password') ?? ''))
        const next=params.get('next')
        router.push(next?.startsWith('/')&&!next.startsWith('//')?next:'/customer/profile')
      } else if (mode === 'register') {
        const contact = value('identifier')
        const result = await authApi.register({ fullName: value('fullName'), password: String(values.get('password') ?? ''),
          ...(contact.includes('@') ? { email: contact } : { phone: contact }) })
        router.push(`/verify?challengeId=${result.verificationChallengeId}`)
      } else if (mode === 'verify') {
        await authApi.verify(params.get('token') ? { verificationToken: params.get('token')! }
          : { challengeId: value('challengeId'), code: value('code') })
        setSuccess('Xác minh thành công. Bạn có thể đăng nhập.');
      } else if (mode === 'forgot') {
        await authApi.forgot(value('identifier'))
        setSuccess('Nếu tài khoản hợp lệ, hướng dẫn đã được gửi. Ở local, xem email hoặc SMS demo trong Mailpit.')
      } else {
        await authApi.reset({ challengeId: value('challengeId'), code: value('code'), newPassword: String(values.get('password') ?? '') })
        setSuccess('Mật khẩu đã được cập nhật và các phiên cũ đã thu hồi. Hãy đăng nhập lại.')
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Có lỗi xảy ra.')
      if (err instanceof ApiError) setFieldErrors(Object.fromEntries(err.fieldErrors.map(item => [item.field, item.message])))
    } finally { setBusy(false) }
  }
  function field(name: string, label: string, props: React.InputHTMLAttributes<HTMLInputElement> = {}) {
    return <label className="grid gap-2 text-sm font-medium">{label}<Input name={name} required disabled={busy} {...props} aria-invalid={Boolean(fieldErrors[name])} />
      {fieldErrors[name] && <span className="text-xs text-red-700">{fieldErrors[name]}</span>}</label>
  }
  return <Card className="mx-auto w-full max-w-md shadow-sm"><CardHeader><CardTitle className="text-2xl">{titles[mode]}</CardTitle>
    <p className="text-sm text-slate-500">Đặt sân, quản lý cơ sở và theo dõi lịch chơi của bạn.</p></CardHeader>
    <CardContent><form onSubmit={submit} className="grid gap-5">
      {mode === 'register' && field('fullName', 'Họ và tên', { minLength: 2, maxLength: 120, autoComplete: 'name' })}
      {['login','register','forgot'].includes(mode) && field('identifier', 'Email hoặc số điện thoại quốc tế', { autoComplete: 'username', placeholder: 'ban@example.com hoặc +84901234567' })}
      {['login','register','reset'].includes(mode) && field('password', mode === 'reset' ? 'Mật khẩu mới' : 'Mật khẩu', { type: 'password', minLength: mode === 'login' ? 1 : 8, maxLength: 72, autoComplete: mode === 'login' ? 'current-password' : 'new-password' })}
      {['verify','reset'].includes(mode) && !params.get('token') && <>
        {field('challengeId', 'Mã yêu cầu xác minh', { defaultValue: params.get('challengeId') ?? '', readOnly: Boolean(params.get('challengeId')) })}
        {field('code', 'Mã xác minh 6 chữ số', { pattern: '[0-9]{6}', inputMode: 'numeric', maxLength: 6, autoComplete: 'one-time-code' })}
        <p className="text-xs text-slate-500">Mã và liên kết được gửi qua kênh liên hệ.{process.env.NEXT_PUBLIC_DEMO_MAILPIT_URL&&<> Local/demo: mở <a className="underline" href={process.env.NEXT_PUBLIC_DEMO_MAILPIT_URL} target="_blank" rel="noreferrer">Mailpit</a>.</>}</p>
      </>}
      {error && <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{error}</p>}
      {success && <p role="status" className="rounded-lg bg-emerald-50 p-3 text-sm text-emerald-800">{success} <Link className="underline" href="/login">Đăng nhập</Link></p>}
      <Button className="h-11 bg-emerald-600 text-white hover:bg-emerald-700" type="submit" disabled={busy}>{busy ? 'Đang xử lý…' : mode === 'login' ? 'Đăng nhập' : mode === 'register' ? 'Tạo tài khoản' : mode === 'verify' ? 'Xác minh' : 'Tiếp tục'}</Button>
      <div className="flex flex-wrap justify-between gap-3 text-sm text-emerald-700"><Link href={mode === 'login' ? '/register' : '/login'}>{mode === 'login' ? 'Tạo tài khoản' : 'Quay lại đăng nhập'}</Link><Link href="/forgot-password">Quên mật khẩu?</Link></div>
    </form></CardContent></Card>
}
