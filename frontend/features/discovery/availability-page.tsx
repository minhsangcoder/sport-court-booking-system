'use client'
import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useApi } from '@/lib/use-api'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import type { Facility,Court } from '@/features/facility/types'
import { type Preview,type Slot,money } from '@/features/schedule/types'
import type { Hold } from '@/features/booking/types'
const reasons:Record<string,string>={MAINTENANCE:'Bảo trì',CLOSED_EXCEPTION:'Đóng cửa',PRICE_NOT_CONFIGURED:'Chưa có giá',AMBIGUOUS_PRICE:'Giá cần được kiểm tra',COURT_DISABLED:'Sân tạm ngừng',PAST_SLOT:'Đã qua giờ',HELD:'Đang giữ chỗ',BOOKED:'Đã được đặt'}
export function AvailabilityPage({facilityId,courtId,counter=false}:{facilityId:string;courtId:string;counter?:boolean}){
 const router=useRouter();const {user}=useAuth();const facility=useApi<Facility>(`/facilities/${facilityId}`);const courts=useApi<Court[]>(`/facilities/${facilityId}/courts`)
 const [date,setDate]=useState(new Date().toLocaleDateString('en-CA'));const resource=useApi<Preview>(`/bookings/availability?courtId=${courtId}&date=${date}`);const [selection,setSelection]=useState<Slot[]>([]);const [busy,setBusy]=useState(false);const [error,setError]=useState('')
 const court=courts.data?.find(c=>c.id===courtId);const total=selection.reduce((v,s)=>v+(s.amount||0),0)
 function select(slot:Slot){setError('');setSelection(current=>current.some(s=>s.startsAt===slot.startsAt)?current.filter(s=>s.startsAt!==slot.startsAt):[...current,slot].sort((a,b)=>a.startsAt.localeCompare(b.startsAt)))}
 async function hold(){if(busy||selection.length===0)return;if(!user){router.push(`/login?next=${encodeURIComponent(`/facilities/${facilityId}/courts/${courtId}`)}`);return}setBusy(true);setError('')
  try{for(let i=1;i<selection.length;i++)if(selection[i-1].endsAt!==selection[i].startsAt)throw new Error('Chọn các slot liên tiếp cho một lượt đặt.');const h=await api<Hold>(`/bookings/holds${counter?'?counter=true':''}`,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({courtId,startsAt:selection[0].startsAt,endsAt:selection[selection.length-1].endsAt,expectedAmount:total})});router.push(`/customer/booking/holds/${h.id}${counter?'?counter=true':''}`)}
  catch(err){setError(err instanceof Error?err.message:'Không thể giữ chỗ.');resource.reload();setSelection([])}finally{setBusy(false)}
 }
 const timezone=facility.data?.timezone||resource.data?.timezone
 const time=(v:string)=>new Date(v).toLocaleTimeString('vi-VN',{timeZone:timezone,hour:'2-digit',minute:'2-digit'})
 return <div className="mx-auto max-w-6xl px-5 py-8"><Link className="text-sm text-slate-500" href={`/facilities/${facilityId}`}>{facility.data?.name||'Cơ sở'} / {court?.name||'Sân'}</Link><h1 className="mt-5 text-3xl font-black">Chọn giờ chơi</h1><p className="mt-3 text-sm text-slate-500">Lịch trống cập nhật từ hệ thống. Chọn slot chưa giữ chỗ; giờ theo {timezone||'cơ sở'}.</p><div className="my-6 flex flex-wrap items-end justify-between gap-4"><label className="grid gap-2 text-sm">Ngày chơi<Input type="date" value={date} min={new Date().toLocaleDateString('en-CA')} onChange={e=>{setDate(e.target.value);setSelection([])}} required disabled={busy}/></label><Button variant="outline" onClick={()=>{resource.reload();setSelection([])}} disabled={busy}>Làm mới lịch</Button></div>
  {(resource.error||facility.error||courts.error||error)&&<p role="alert" className="mb-5 rounded-lg bg-red-50 p-4 text-sm text-red-700">{error||resource.error||facility.error||courts.error}</p>}{resource.loading&&<p role="status">Đang kiểm tra lịch trống…</p>}{resource.data?.slots.length===0&&<p className="rounded-xl border p-6 text-slate-500">Không có khung giờ mở cửa trong ngày này. Hãy chọn ngày khác.</p>}
  <div className="grid gap-3 grid-cols-2 sm:grid-cols-3 lg:grid-cols-4">{resource.data?.slots.map(s=>{const selected=selection.some(v=>v.startsAt===s.startsAt);return <button key={s.startsAt} type="button" disabled={busy||resource.loading||s.state!=='AVAILABLE'} onClick={()=>select(s)} aria-pressed={selected} className={`rounded-xl border p-4 text-left transition ${selected?'border-emerald-600 bg-emerald-600 text-white':s.state==='AVAILABLE'?'border-emerald-200 bg-emerald-50 hover:border-emerald-500':'border-slate-200 bg-slate-100 text-slate-400'}`}><span className="block font-semibold">{time(s.startsAt)}–{time(s.endsAt)}</span><span className="mt-2 block text-sm">{s.amount===null?'Chưa có giá':money(s.amount,s.currency)}</span><span className="mt-2 block text-xs">{s.reason?reasons[s.reason]||s.reason:'Còn trống'}</span></button>})}</div>
  {selection.length>0&&<aside className="sticky bottom-3 mt-8 flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-emerald-200 bg-white p-5 shadow-xl"><div><p className="text-sm text-slate-500">{selection.length} slot · {time(selection[0].startsAt)}–{time(selection[selection.length-1].endsAt)}</p><p className="mt-1 text-2xl font-bold">{money(total)}</p></div><Button onClick={hold} disabled={busy||resource.loading} className="bg-emerald-600 px-6 text-white">{busy?'Đang kiểm tra…':user?'Xem tóm tắt và giữ chỗ':'Đăng nhập để đặt sân'}</Button></aside>}
 </div>
}
