'use client'
import Link from 'next/link'
import { usePathname,useRouter } from 'next/navigation'
import { useAuth } from '@/lib/auth-context'
import type { Role } from '@/lib/api'
export function RequireAuth({ children, roles }: { children: React.ReactNode; roles?: Role[] }) {
  const { user, ready } = useAuth()
  const pathname=usePathname();const router=useRouter()
  if (!ready) return <p role="status" className="p-8">Đang kiểm tra phiên đăng nhập…</p>
  if (!user) return <div className="p-8"><h1 className="text-xl font-bold">Vui lòng đăng nhập</h1><Link className="mt-3 inline-block text-emerald-700 underline" href={`/login?next=${encodeURIComponent(pathname)}`} onClick={e=>{e.preventDefault();router.push(`/login?next=${encodeURIComponent(window.location.pathname+window.location.search)}`)}}>Đăng nhập để tiếp tục</Link></div>
  if (roles && !roles.some(role => user.roles.includes(role))) return <p role="alert" className="p-8 text-red-700">Tài khoản không có quyền truy cập khu vực này.</p>
  return children
}
