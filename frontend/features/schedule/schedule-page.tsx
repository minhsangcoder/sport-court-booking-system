'use client'
import Link from 'next/link'
import { useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { useFacility } from '@/features/facility/facility-context'
import type { Court } from '@/features/facility/types'
import { weekdays,type Hours,type ScheduleException } from './types'
type Interval=Pick<Hours,'dayOfWeek'|'opensAt'|'closesAt'|'slotMinutes'>
const defaultInterval:Interval={dayOfWeek:1,opensAt:'06:00',closesAt:'22:00',slotMinutes:60}
export function SchedulePage(){
 const {facility,basePath}=useFacility();const hours=useApi<Hours[]>(`/schedules/facilities/${facility.id}/hours`)
 const courts=useApi<Court[]>(`/owner/facilities/${facility.id}/courts`);const [scope,setScope]=useState('')
 return <section className="space-y-6"><div className="flex flex-wrap items-center justify-between gap-4"><div><h2 className="text-2xl font-bold">Lịch mở cửa</h2><p className="mt-2 text-sm text-slate-500">Giờ theo {facility.timezone}. Sân dùng lịch cơ sở khi chưa có lịch riêng.</p></div><Link className="rounded-full bg-emerald-600 px-5 py-2 text-sm text-white" href={`${basePath}/schedule/preview`}>Xem trước lịch và giá</Link></div>
  <label className="grid max-w-sm gap-2 text-sm">Phạm vi lịch<select className="rounded-lg border bg-white p-3" value={scope} onChange={e=>setScope(e.target.value)}><option value="">Toàn cơ sở</option>{courts.data?.map(c=><option value={c.id} key={c.id}>{c.name}</option>)}</select></label>
  {(hours.error||courts.error)&&<p role="alert" className="text-red-700">{hours.error||courts.error}<button className="ml-3 underline" onClick={()=>{hours.reload();courts.reload()}}>Thử lại</button></p>}
  {hours.loading?<p role="status">Đang tải lịch…</p>:hours.data&&<HoursEditor key={scope+JSON.stringify(hours.data)} facilityId={facility.id} courtId={scope} initial={hours.data.filter(h=>(h.courtId||'')===scope)} reload={hours.reload}/>}
  <Exceptions facilityId={facility.id} courtId={scope}/>
 </section>
}
function HoursEditor({facilityId,courtId,initial,reload}:{facilityId:string;courtId:string;initial:Interval[];reload:()=>void}){
 const [rows,setRows]=useState<Interval[]>(initial);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [saved,setSaved]=useState(false)
 function update(index:number,key:keyof Interval,value:string){setRows(rows.map((r,i)=>i===index?{...r,[key]:key==='dayOfWeek'||key==='slotMinutes'?Number(value):value}:r))}
 async function save(e:React.FormEvent){e.preventDefault();if(busy)return;setBusy(true);setError('');setSaved(false)
  try{await api(`/schedules/facilities/${facilityId}/hours`,{method:'PUT',body:JSON.stringify({courtId:courtId||null,intervals:rows})});setSaved(true);reload()}catch(err){setError(err instanceof Error?err.message:'Không thể lưu lịch.')}finally{setBusy(false)}
 }
 return <form onSubmit={save} className="space-y-4 rounded-2xl border bg-white p-5"><h3 className="font-bold">Các khoảng mở cửa</h3>{rows.length===0&&<p className="text-sm text-slate-500">Chưa có lịch ở phạm vi này. Thêm khoảng giờ hoặc dùng lịch cơ sở.</p>}
  {rows.map((row,i)=><div className="grid items-end gap-3 border-b pb-4 sm:grid-cols-5" key={i}><label className="grid gap-2 text-xs">Ngày<select aria-label={`Ngày khoảng ${i+1}`} className="rounded-lg border bg-white p-2.5" value={row.dayOfWeek} onChange={e=>update(i,'dayOfWeek',e.target.value)} disabled={busy}>{weekdays.map((day,n)=><option value={n+1} key={day}>{day}</option>)}</select></label>
   <label className="grid gap-2 text-xs">Mở cửa<Input type="time" value={row.opensAt} onChange={e=>update(i,'opensAt',e.target.value)} required disabled={busy}/></label><label className="grid gap-2 text-xs">Đóng cửa<Input type="time" value={row.closesAt} onChange={e=>update(i,'closesAt',e.target.value)} required disabled={busy}/></label><label className="grid gap-2 text-xs">Phút mỗi slot<Input type="number" min={5} max={720} value={row.slotMinutes} onChange={e=>update(i,'slotMinutes',e.target.value)} required disabled={busy}/></label><Button type="button" variant="outline" disabled={busy} onClick={()=>setRows(rows.filter((_,n)=>n!==i))}>Bỏ khoảng</Button></div>)}
  <div className="flex flex-wrap gap-3"><Button type="button" variant="outline" disabled={busy} onClick={()=>setRows([...rows,{...defaultInterval}])}>Thêm khoảng giờ</Button><Button type="submit" disabled={busy}>{busy?'Đang lưu…':'Lưu lịch'}</Button></div>
  {error&&<p role="alert" className="text-sm text-red-700">{error}</p>}{saved&&<p role="status" className="text-sm text-emerald-700">Đã lưu lịch.</p>}<p className="text-xs text-amber-700">Thay đổi lịch không tự hủy lịch đặt đã xác nhận. Kiểm tra các lịch đặt bị ảnh hưởng.</p>
 </form>
}
function Exceptions({facilityId,courtId}:{facilityId:string;courtId:string}){
 const resource=useApi<ScheduleException[]>(`/schedules/facilities/${facilityId}/exceptions`);const [type,setType]=useState('CLOSED');const [busy,setBusy]=useState(false);const [error,setError]=useState('')
 async function save(e:React.FormEvent<HTMLFormElement>){e.preventDefault();if(busy)return;setBusy(true);setError('');const form=e.currentTarget;const v=new FormData(form)
  try{await api(`/schedules/facilities/${facilityId}/exceptions`,{method:'POST',body:JSON.stringify({courtId:courtId||null,date:v.get('date'),type,opensAt:type==='CLOSED'?null:v.get('opensAt'),closesAt:type==='CLOSED'?null:v.get('closesAt'),reason:v.get('reason')})});resource.reload();form.reset()}catch(err){setError(err instanceof Error?err.message:'Không thể lưu ngoại lệ.')}finally{setBusy(false)}
 }
 async function remove(id:string){if(!window.confirm('Bỏ ngoại lệ này?'))return;setBusy(true);setError('');try{await api(`/schedules/facilities/${facilityId}/exceptions/${id}`,{method:'DELETE'});resource.reload()}catch(err){setError(err instanceof Error?err.message:'Không thể bỏ ngoại lệ.')}finally{setBusy(false)}}
 return <div className="rounded-2xl border p-5"><h3 className="font-bold">Ngày nghỉ và giờ đặc biệt</h3><form className="mt-4 grid gap-4 sm:grid-cols-2" onSubmit={save}><label className="grid gap-2 text-sm">Ngày<Input type="date" name="date" required disabled={busy}/></label><label className="grid gap-2 text-sm">Loại ngoại lệ<select className="rounded-lg border bg-white p-3" value={type} onChange={e=>setType(e.target.value)} disabled={busy}><option value="CLOSED">Đóng cửa</option><option value="SPECIAL_HOURS">Giờ đặc biệt</option></select></label>{type==='SPECIAL_HOURS'&&<><label className="grid gap-2 text-sm">Mở cửa<Input type="time" name="opensAt" required disabled={busy}/></label><label className="grid gap-2 text-sm">Đóng cửa<Input type="time" name="closesAt" required disabled={busy}/></label></>}<label className="grid gap-2 text-sm sm:col-span-2">Lý do<Input name="reason" required maxLength={500} disabled={busy}/></label><Button type="submit" disabled={busy}>{busy?'Đang lưu…':'Thêm ngoại lệ'}</Button></form>
  {(resource.error||error)&&<p role="alert" className="mt-4 text-red-700">{error||resource.error}</p>}{resource.loading&&<p role="status" className="mt-4">Đang tải ngoại lệ…</p>}
  <div className="mt-5 space-y-3">{resource.data?.filter(e=>(e.courtId||'')===courtId).map(e=><article className="flex flex-wrap justify-between gap-3 rounded-lg bg-slate-50 p-4" key={e.id}><div className="text-sm"><p className="font-semibold">{e.date} · {e.type==='CLOSED'?'Đóng cửa':`${e.opensAt} – ${e.closesAt}`}</p><p className="mt-1 text-slate-500">{e.reason}</p></div><button className="text-sm text-red-700" disabled={busy} onClick={()=>remove(e.id)}>Bỏ ngoại lệ</button></article>)}{resource.data?.filter(e=>(e.courtId||'')===courtId).length===0&&<p className="text-sm text-slate-500">Chưa có ngoại lệ ở phạm vi này.</p>}</div>
 </div>
}
