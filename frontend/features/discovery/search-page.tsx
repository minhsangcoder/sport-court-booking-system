'use client'
import Link from 'next/link'
import { useState } from 'react'
import { MapPin,ArrowRight } from 'lucide-react'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import type { Facility } from '@/features/facility/types'
export function SearchPage(){
 const [query,setQuery]=useState('');const resource=useApi<Facility[]>(`/facilities?q=${encodeURIComponent(query)}`)
 return <div className="mx-auto max-w-6xl px-5 py-10"><div className="rounded-3xl bg-emerald-900 p-7 text-white sm:p-10"><p className="text-sm font-semibold uppercase tracking-widest text-emerald-300">Lịch chơi bắt đầu tại đây</p><h1 className="mt-3 text-3xl font-black sm:text-4xl">Tìm sân cho buổi chơi tiếp theo</h1><p className="mt-4 max-w-xl text-emerald-100">Khám phá cơ sở, chọn sân và kiểm tra khung giờ còn trống.</p><form className="mt-7 flex max-w-2xl gap-3" onSubmit={e=>{e.preventDefault();setQuery(String(new FormData(e.currentTarget).get('q')||''))}}><Input name="q" aria-label="Tên cơ sở hoặc khu vực" placeholder="Tên cơ sở hoặc khu vực…" className="h-12 bg-white text-slate-900" defaultValue={query}/><Button type="submit" className="h-12 bg-amber-400 px-6 text-emerald-950 hover:bg-amber-300">Tìm sân</Button></form></div>
  <div className="mt-8 flex justify-between"><h2 className="text-xl font-bold">{query?`Kết quả cho “${query}”`:'Các cơ sở đang nhận đặt sân'}</h2>{resource.data&&<p className="text-sm text-slate-500">{resource.data.length} cơ sở</p>}</div>
  {resource.loading&&<p role="status" className="mt-6">Đang tìm cơ sở…</p>}{resource.error&&<p role="alert" className="mt-6 text-red-700">{resource.error}<button className="ml-3 underline" onClick={resource.reload}>Thử lại</button></p>}{resource.data?.length===0&&<div className="my-8 rounded-2xl border p-8"><h3 className="font-bold">Chưa tìm thấy cơ sở phù hợp</h3><p className="mt-2 text-slate-500">Thử tên khác hoặc mở rộng khu vực tìm kiếm.</p></div>}
  <div className="mt-6 grid gap-5 md:grid-cols-2 lg:grid-cols-3">{resource.data?.map(f=><article key={f.id} className="flex flex-col rounded-2xl border bg-white p-6 shadow-sm"><div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-700"><MapPin size={24}/></div><h3 className="text-xl font-bold">{f.name}</h3><p className="mt-2 text-sm text-slate-500">{f.addressLine} · {f.district}, {f.province}</p><div className="my-5 flex flex-wrap gap-2">{f.amenities.map(a=><span className="rounded-full bg-slate-100 px-3 py-1 text-xs" key={a}>{a}</span>)}</div><Link href={`/facilities/${f.id}`} className="mt-auto flex items-center justify-between border-t pt-4 text-sm font-semibold text-emerald-700">Xem sân và lịch trống<ArrowRight size={18}/></Link></article>)}</div>
 </div>
}
