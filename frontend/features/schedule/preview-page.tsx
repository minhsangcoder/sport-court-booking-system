'use client'
import { useState } from 'react'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
import { useFacility } from '@/features/facility/facility-context'
import type { Court } from '@/features/facility/types'
import { type Preview,money } from './types'
export function PreviewPage(){
 const {facility}=useFacility();const courts=useApi<Court[]>(`/owner/facilities/${facility.id}/courts`);const [selection,setSelection]=useState('');const [date,setDate]=useState(new Date().toLocaleDateString('en-CA'))
 const courtId=selection||courts.data?.[0]?.id;const resource=useApi<Preview>(courtId?`/schedules/facilities/${facility.id}/preview?courtId=${courtId}&date=${date}`:null)
 return <section><h2 className="text-2xl font-bold">Xem trước lịch và giá</h2><p className="mt-2 text-sm text-slate-500">Giờ theo {facility.timezone}. Bản xem trước xét lịch và bảo trì; lượt đặt và giữ chỗ được xét tại bước đặt sân.</p><div className="my-6 flex flex-wrap gap-4"><label className="grid gap-2 text-sm">Sân<select className="rounded-lg border bg-white p-3" value={courtId||''} onChange={e=>setSelection(e.target.value)}>{courts.data?.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label><label className="grid gap-2 text-sm">Ngày<Input type="date" value={date} onChange={e=>setDate(e.target.value)} required/></label></div>
  {(courts.error||resource.error)&&<p role="alert" className="text-red-700">{courts.error||resource.error}<button className="ml-3 underline" onClick={resource.reload}>Thử lại</button></p>}{(courts.loading||resource.loading)&&<p role="status">Đang tải lịch…</p>}{courts.data?.length===0&&<p className="text-slate-500">Tạo sân trước khi xem lịch.</p>}{resource.data?.slots.length===0&&<p className="text-slate-500">Chưa có giờ mở cửa trong ngày này.</p>}
  <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-4">{resource.data?.slots.map(s=><article key={s.startsAt} className={`rounded-xl border p-4 ${s.state==='ELIGIBLE'?'border-emerald-200 bg-emerald-50':'border-slate-200 bg-slate-100'}`}><h3 className="font-semibold">{new Date(s.startsAt).toLocaleTimeString('vi-VN',{timeZone:facility.timezone,hour:'2-digit',minute:'2-digit'})}–{new Date(s.endsAt).toLocaleTimeString('vi-VN',{timeZone:facility.timezone,hour:'2-digit',minute:'2-digit'})}</h3><p className="mt-2 font-bold">{s.amount===null?'Chưa có giá':money(s.amount,s.currency)}</p><p className="mt-2 text-xs text-slate-600">{s.reason||s.ruleLabel}</p>{s.ruleId&&<details className="mt-2 text-xs text-slate-500"><summary>Quy tắc áp dụng</summary><p className="mt-1 break-all">{s.ruleId} · phiên bản {s.ruleVersion}</p></details>}</article>)}</div>
 </section>
}
