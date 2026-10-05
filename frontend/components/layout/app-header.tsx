'use client'
import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useAuth } from '@/lib/auth-context'
import { authApi } from '@/lib/api'
export function AppHeader() {
  const { user, ready } = useAuth(); const router = useRouter(); const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function logout() { setBusy(true); setError(''); try { await authApi.logout(); router.push('/login') }
    catch (err) { setError(err instanceof Error ? err.message : 'Không thể đăng xuất') } finally { setBusy(false) } }
  return <header className="border-b bg-white"><div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-5 py-4">
    <Link className="text-2xl font-black tracking-tight text-emerald-700" href="/">SportHub<span className="text-amber-500">.</span></Link>
    <nav aria-label="Điều hướng chính" className="flex flex-wrap items-center gap-4 text-sm font-medium">
      <Link href="/search">Tìm sân</Link>
      {user && <><Link href="/customer/bookings">Lịch đặt</Link><Link href="/customer/groups">Nhóm</Link><Link href="/customer/profile">{user.fullName}</Link></>}
      {user?.roles.includes('OWNER') && <Link href="/owner/facilities">Cơ sở của tôi</Link>}
      {user?.roles.includes('STAFF') && <><Link href="/staff/bookings">Vận hành</Link><Link href="/staff/schedule">Lịch và giá</Link></>}
      {user?.roles.includes('ADMIN') && <Link href="/admin/accounts">Quản trị</Link>}
      {!ready ? <span className="text-slate-400">Đang kiểm tra phiên…</span> : user ? <button onClick={logout} disabled={busy} className="text-slate-500">{busy ? 'Đang đăng xuất…' : 'Đăng xuất'}</button> : <Link className="rounded-full bg-emerald-600 px-5 py-2 text-white" href="/login">Đăng nhập</Link>}
    </nav>{error && <p role="alert" className="w-full text-sm text-red-700">{error}</p>}
  </div></header>
}
