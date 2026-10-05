'use client'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { createContext,useContext } from 'react'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Facility } from './types'
const FacilityContext=createContext<{facility:Facility;reload:()=>void}|null>(null)
export function FacilityShell({id,children}:{id:string;children:React.ReactNode}){return <RequireAuth roles={['OWNER']}><LoadedShell id={id}>{children}</LoadedShell></RequireAuth>}
function LoadedShell({id,children}:{id:string;children:React.ReactNode}){
  const {data,error,loading,reload}=useApi<Facility>(`/owner/facilities/${id}`);const path=usePathname()
  if(loading)return <p className="p-8" role="status">Đang tải cơ sở…</p>
  if(error||!data)return <div role="alert" className="p-8 text-red-700">{error}<button className="ml-3 underline" onClick={reload}>Thử lại</button></div>
  const base=`/owner/facilities/${id}`
  return <FacilityContext.Provider value={{facility:data,reload}}><div className="mx-auto max-w-6xl px-5 py-8"><p className="text-sm text-slate-500"><Link className="hover:underline" href="/owner/facilities">Cơ sở</Link> / {data.name}</p><div className="my-6 flex flex-wrap items-center justify-between gap-3"><div><h1 className="text-3xl font-bold">{data.name}</h1><p className="mt-2 text-sm text-slate-500">{data.addressLine} · {data.timezone}</p></div><span className="rounded-full bg-emerald-50 px-4 py-2 text-sm text-emerald-700">{data.status}</span></div>
    <nav aria-label="Điều hướng cơ sở" className="mb-8 flex gap-2 overflow-x-auto border-b pb-3">{[['','Tổng quan'],['/courts','Sân'],['/schedule','Lịch mở cửa'],['/pricing','Bảng giá'],['/maintenance','Bảo trì'],['/staff','Nhân viên'],['/transfer-policy','Chuyển nhượng'],['/reports','Báo cáo'],['/edit','Chỉnh sửa']].map(([suffix,label])=><Link key={suffix} href={base+suffix} className={`whitespace-nowrap rounded-full px-4 py-2 text-sm ${path===base+suffix?'bg-emerald-600 text-white':'bg-slate-100 text-slate-600'}`}>{label}</Link>)}</nav>
    {children}</div></FacilityContext.Provider>
}
export function useFacility(){const value=useContext(FacilityContext);if(!value)throw new Error('Facility context is required');return value}
