'use client'
import Link from 'next/link'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Facility } from './types'
export function FacilityListPage(){return <RequireAuth roles={['OWNER']}><FacilityList/></RequireAuth>}
function FacilityList(){
  const {data,error,loading,reload}=useApi<Facility[]>('/owner/facilities')
  return <div className="mx-auto max-w-6xl px-5 py-10"><div className="flex flex-wrap items-center justify-between gap-4"><div><p className="text-sm font-semibold text-emerald-600">Không gian chủ cơ sở</p><h1 className="mt-2 text-3xl font-bold">Cơ sở của tôi</h1></div><Link className="rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white" href="/owner/facilities/new">Tạo cơ sở</Link></div>
    {loading&&<p role="status" className="mt-8">Đang tải cơ sở…</p>}
    {error&&<div role="alert" className="mt-8 rounded-lg bg-red-50 p-5 text-red-700">{error}<button className="ml-4 underline" onClick={reload}>Thử lại</button></div>}
    {data?.length===0&&<div className="mt-8 rounded-2xl border border-dashed p-10 text-center"><h2 className="text-xl font-bold">Chưa có cơ sở</h2><p className="mt-2 text-slate-500">Tạo hồ sơ cơ sở đầu tiên để bắt đầu quản lý sân.</p></div>}
    <div className="mt-8 grid gap-5 md:grid-cols-2">{data?.map(f=><Link key={f.id} href={`/owner/facilities/${f.id}`} className="rounded-2xl border bg-white p-6 shadow-sm transition hover:border-emerald-400"><span className="rounded-full bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700">{f.status}</span><h2 className="mt-4 text-xl font-bold">{f.name}</h2><p className="mt-2 text-sm text-slate-500">{f.addressLine}, {f.district}, {f.province}</p><p className="mt-5 text-sm font-semibold text-emerald-700">Quản lý cơ sở →</p></Link>)}</div>
  </div>
}
