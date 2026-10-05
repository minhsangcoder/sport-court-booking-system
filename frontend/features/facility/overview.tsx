'use client'
import Image from 'next/image'
import { useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Button } from '@/components/ui/button'
import { useFacility } from './facility-context'
import type { Media } from './types'
export function FacilityOverview(){
 const {facility}=useFacility();const images=useApi<Media[]>(`/owner/facilities/${facility.id}/images`);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [message,setMessage]=useState('')
 const [replacement,setReplacement]=useState<string|null>(null)
 async function upload(event:React.FormEvent<HTMLFormElement>){event.preventDefault();if(busy)return;setBusy(true);setError('');setMessage('');const formElement=event.currentTarget
  try{await api(`/owner/facilities/${facility.id}/images${replacement?'/'+replacement:''}`,{method:replacement?'PUT':'POST',body:new FormData(formElement)});images.reload();formElement.reset();setMessage(replacement?'Đã thay ảnh.':'Đã tải ảnh lên.');setReplacement(null)}
  catch(err){setError(err instanceof Error?err.message:'Không thể tải ảnh lên.')}finally{setBusy(false)}
 }
 async function remove(id:string){if(!window.confirm('Xóa ảnh này khỏi cơ sở?'))return;setBusy(true);setError('');try{await api(`/owner/facilities/${facility.id}/images/${id}`,{method:'DELETE'});images.reload();setMessage('Đã xóa ảnh.')}catch(err){setError(err instanceof Error?err.message:'Không thể xóa ảnh.')}finally{setBusy(false)}}
 return <section className="grid gap-8"><div className="rounded-xl border p-6"><h2 className="text-xl font-bold">Thông tin cơ sở</h2><p className="mt-3 text-slate-600">{facility.description||'Chưa có mô tả.'}</p><p className="mt-3 text-sm">Liên hệ: {facility.phone}</p><div className="mt-4 flex flex-wrap gap-2">{facility.amenities.map(a=><span key={a} className="rounded-full bg-slate-100 px-3 py-1 text-sm">{a}</span>)}</div>
  {facility.status==='DRAFT'&&<p className="mt-4 text-sm text-amber-700">Hồ sơ nháp chưa xuất hiện trên tìm kiếm công khai. Quy trình phê duyệt được xử lý riêng.</p>}</div>
  <div><h2 className="text-xl font-bold">Ảnh cơ sở</h2>{replacement&&<p className="mt-3 text-sm text-emerald-700">Chọn ảnh mới để thay ảnh đã chọn. Ảnh hiện tại được giữ đến khi lưu thành công.</p>}<form className="mt-4 flex flex-wrap items-center gap-3" onSubmit={upload}><input name="file" type="file" accept="image/png,image/jpeg" required disabled={busy} className="max-w-full text-sm"/><Button type="submit" disabled={busy}>{busy?'Đang xử lý…':replacement?'Lưu ảnh thay thế':'Tải ảnh lên'}</Button>{replacement&&<Button variant="outline" type="button" disabled={busy} onClick={()=>setReplacement(null)}>Hủy thay ảnh</Button>}</form><p className="mt-2 text-xs text-slate-500">JPEG/PNG, tối đa 10 MB trong demo. Ảnh lưu trong object storage.</p>
   {images.loading&&<p role="status" className="mt-4">Đang tải ảnh…</p>}{(error||images.error)&&<p role="alert" className="mt-4 text-red-700">{error||images.error}</p>}{message&&<p role="status" className="mt-4 text-emerald-700">{message}</p>}{images.data?.length===0&&<p className="mt-4 text-slate-500">Chưa có ảnh.</p>}
   <div className="mt-5 grid gap-4 sm:grid-cols-2 md:grid-cols-3">{images.data?.map(image=><figure key={image.id} className="overflow-hidden rounded-xl border"><Image unoptimized src={image.url} alt="Ảnh cơ sở" width={400} height={240} className="h-48 w-full object-cover"/><figcaption className="flex gap-4 p-3"><button className="text-sm text-emerald-700" onClick={()=>setReplacement(image.id)} disabled={busy}>Thay ảnh</button><button className="text-sm text-red-700" onClick={()=>remove(image.id)} disabled={busy}>Xóa ảnh</button></figcaption></figure>)}</div>
  </div></section>
}
