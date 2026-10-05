'use client'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { api,ApiError } from '@/lib/api'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import type { Facility,FacilityInput } from './types'
export function FacilityForm({facility,onSaved,redirectPath}:{facility?:Facility;onSaved?:()=>void;redirectPath?:string}){
 const router=useRouter();const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [fields,setFields]=useState<Record<string,string>>({})
 async function save(event:React.FormEvent<HTMLFormElement>){event.preventDefault();if(busy)return;setBusy(true);setError('');setFields({});const form=new FormData(event.currentTarget);const text=(name:string)=>String(form.get(name)??'').trim()
  const body:FacilityInput={name:text('name'),phone:text('phone'),addressLine:text('addressLine'),province:text('province'),district:text('district'),ward:text('ward'),description:text('description'),timezone:text('timezone'),amenities:text('amenities').split(',').map(x=>x.trim()).filter(Boolean)}
  body.latitude=facility?.latitude;body.longitude=facility?.longitude
  try{const saved=await api<Facility>(facility?`/owner/facilities/${facility.id}`:'/owner/facilities',{method:facility?'PUT':'POST',body:JSON.stringify(body)});onSaved?.();router.push(redirectPath??`/owner/facilities/${saved.id}`)}
  catch(err){setError(err instanceof Error?err.message:'Không thể lưu cơ sở.');if(err instanceof ApiError)setFields(Object.fromEntries(err.fieldErrors.map(f=>[f.field,f.message])))}finally{setBusy(false)}
 }
 function field(name:keyof FacilityInput,label:string,max:number,required=true){return <label className="grid gap-2 text-sm font-medium">{label}<Input name={name} defaultValue={String(facility?.[name]??(name==='timezone'?'Asia/Ho_Chi_Minh':''))} maxLength={max} required={required} disabled={busy} aria-invalid={!!fields[name]}/>{fields[name]&&<span className="text-xs text-red-700">{fields[name]}</span>}</label>}
 return <form onSubmit={save} className="grid gap-5 rounded-2xl border bg-white p-6 md:grid-cols-2">{field('name','Tên cơ sở',180)}{field('phone','Số điện thoại',30)}{field('addressLine','Địa chỉ',500)}{field('province','Tỉnh / thành phố',100)}{field('district','Quận / huyện',100)}{field('ward','Phường / xã',100)}{field('timezone','Múi giờ IANA',80)}
  <label className="grid gap-2 text-sm font-medium">Tiện ích, phân cách bằng dấu phẩy<Input name="amenities" defaultValue={facility?.amenities.join(', ')} maxLength={1200} disabled={busy}/></label>
  <label className="grid gap-2 text-sm font-medium md:col-span-2">Mô tả<textarea className="min-h-28 rounded-lg border p-3" name="description" defaultValue={facility?.description} maxLength={5000} disabled={busy}/></label>
  {error&&<p role="alert" className="text-red-700 md:col-span-2">{error}</p>}
  <div className="flex gap-3 md:col-span-2"><Button disabled={busy} className="bg-emerald-600 text-white" type="submit">{busy?'Đang lưu…':facility?'Lưu thay đổi':'Tạo cơ sở'}</Button><Button type="button" variant="outline" onClick={()=>router.back()} disabled={busy}>Quay lại</Button></div>
 </form>
}
