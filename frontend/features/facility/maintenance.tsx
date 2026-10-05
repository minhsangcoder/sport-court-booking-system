'use client'
import { useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useFacility } from './facility-context'
import type { Court,Maintenance } from './types'
export function MaintenancePage(){
 const {facility}=useFacility();const courts=useApi<Court[]>(`/owner/facilities/${facility.id}/courts`);const [selection,setSelection]=useState('')
 const courtId=selection||courts.data?.[0]?.id;const windows=useApi<Maintenance[]>(courtId?`/owner/courts/${courtId}/maintenance`:null)
 const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [message,setMessage]=useState('')
 async function save(event:React.FormEvent<HTMLFormElement>){event.preventDefault();if(busy||!courtId)return;setBusy(true);setError('');setMessage('');const form=new FormData(event.currentTarget)
  try{await api(`/owner/courts/${courtId}/maintenance`,{method:'POST',body:JSON.stringify({startsAt:new Date(String(form.get('startsAt'))).toISOString(),endsAt:new Date(String(form.get('endsAt'))).toISOString(),reason:form.get('reason')})});windows.reload();setMessage('Đã lưu thời gian bảo trì.')}
  catch(err){setError(err instanceof Error?err.message:'Không thể lưu bảo trì.')}finally{setBusy(false)}
 }
 async function cancel(id:string){if(!window.confirm('Hủy thời gian bảo trì này?'))return;setBusy(true);setError('');try{await api(`/owner/maintenance/${id}`,{method:'DELETE'});windows.reload()}catch(err){setError(err instanceof Error?err.message:'Không thể hủy bảo trì.')}finally{setBusy(false)}}
 return <section><h2 className="text-2xl font-bold">Bảo trì sân</h2>{courts.loading&&<p role="status">Đang tải sân…</p>}{courts.error&&<p role="alert" className="text-red-700">{courts.error}</p>}{courts.data?.length===0&&<p className="mt-5 text-slate-500">Tạo sân trước khi thiết lập bảo trì.</p>}
  {courtId&&<><select aria-label="Chọn sân bảo trì" className="my-5 rounded-lg border bg-white p-3" value={courtId} onChange={e=>setSelection(e.target.value)} disabled={busy}>{courts.data?.map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select>
   <form onSubmit={save} className="grid max-w-2xl gap-4 rounded-xl border p-5 sm:grid-cols-2"><label className="grid gap-2 text-sm">Bắt đầu<Input name="startsAt" type="datetime-local" required disabled={busy}/></label><label className="grid gap-2 text-sm">Kết thúc<Input name="endsAt" type="datetime-local" required disabled={busy}/></label><label className="grid gap-2 text-sm sm:col-span-2">Lý do<Input name="reason" maxLength={1200} required disabled={busy}/></label><Button className="bg-emerald-600 text-white sm:col-span-2" disabled={busy} type="submit">{busy?'Đang lưu…':'Tạo thời gian bảo trì'}</Button></form>
   <p className="mt-3 text-sm text-amber-700">Không tự hủy booking đã xác nhận. Cần xem và xử lý booking bị ảnh hưởng theo policy được chốt.</p>
   {(error||windows.error)&&<p className="mt-4 text-red-700" role="alert">{error||windows.error}</p>}{message&&<p className="mt-4 text-emerald-700" role="status">{message}</p>}{windows.loading&&<p role="status" className="mt-4">Đang tải bảo trì…</p>}{windows.data?.length===0&&<p className="mt-5 text-slate-500">Chưa có thời gian bảo trì.</p>}
   <div className="mt-6 grid gap-3">{windows.data?.map(w=><article className="flex flex-wrap items-center justify-between gap-3 rounded-xl border p-4" key={w.id}><div><p className="font-semibold">{w.reason}</p><p className="mt-1 text-sm text-slate-500">{new Date(w.startsAt).toLocaleString('vi-VN')} → {new Date(w.endsAt).toLocaleString('vi-VN')}</p></div><button className="text-sm text-red-700" onClick={()=>cancel(w.id)} disabled={busy}>Hủy bảo trì</button></article>)}</div>
  </>}
 </section>
}
