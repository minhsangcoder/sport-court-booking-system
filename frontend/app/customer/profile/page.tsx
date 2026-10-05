'use client'
import { useState } from 'react'
import { api, ApiError, type UserProfile } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'
import { RequireAuth } from '@/features/auth/require-auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
export default function ProfilePage() {
  const { user } = useAuth(); const [busy,setBusy] = useState(false); const [message,setMessage] = useState(''); const [error,setError] = useState('')
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if(busy) return; setBusy(true); setMessage(''); setError('')
    const form = new FormData(event.currentTarget)
    try {
      const updated = await api<UserProfile>('/users/me', { method:'PATCH', body:JSON.stringify({fullName:form.get('fullName'), avatarUrl:form.get('avatarUrl') || null, dateOfBirth:form.get('dateOfBirth') || null}) })
      window.dispatchEvent(new CustomEvent('sporthub-session',{detail:updated})); setMessage('Đã lưu hồ sơ.')
    } catch(err) {setError(err instanceof ApiError ? err.message : 'Không thể lưu hồ sơ.')} finally{setBusy(false)}
  }
  return <RequireAuth><div className="mx-auto max-w-2xl px-5 py-10"><h1 className="text-3xl font-bold">Hồ sơ của tôi</h1><p className="mt-2 text-slate-500">{user?.email ?? user?.phone} · {user?.roles.join(', ')}</p>
    <form onSubmit={save} className="mt-8 grid gap-5 rounded-2xl border bg-white p-6" key={user?.id}>
      <label className="grid gap-2 text-sm">Họ và tên<Input name="fullName" defaultValue={user?.fullName} minLength={2} maxLength={120} required disabled={busy}/></label>
      <label className="grid gap-2 text-sm">Ngày sinh<Input name="dateOfBirth" type="date" defaultValue={user?.dateOfBirth ?? ''} disabled={busy}/></label>
      <label className="grid gap-2 text-sm">URL ảnh đại diện<Input name="avatarUrl" type="url" defaultValue={user?.avatarUrl ?? ''} maxLength={2048} disabled={busy}/></label>
      {error && <p role="alert" className="text-red-700">{error}</p>}{message && <p role="status" className="text-emerald-700">{message}</p>}
      <Button type="submit" disabled={busy} className="bg-emerald-600 text-white">{busy?'Đang lưu…':'Lưu hồ sơ'}</Button>
    </form></div></RequireAuth>
}
