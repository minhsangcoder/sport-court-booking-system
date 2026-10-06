'use client'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { api,ApiError } from '@/lib/api'
import { Button } from '@/components/ui/button'
import type { FacilityProfile,FacilityInput } from './types'
import type { FacilityLocation } from './location-picker'
import { FacilityFields,facilityFrom } from './facility-fields'
export function FacilityForm({facility,onSaved,redirectPath}:{facility?:FacilityProfile;onSaved?:()=>void;redirectPath?:string}){
 const router=useRouter();const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [fields,setFields]=useState<Record<string,string>>({})
 const [location,setLocation]=useState<FacilityLocation>(()=>facility?.latitude!=null&&facility.longitude!=null?{latitude:facility.latitude,longitude:facility.longitude}:null)
 const frozen=!!facility&&['PENDING_APPROVAL','REJECTED'].includes(facility.status)
 async function save(event:React.FormEvent<HTMLFormElement>){event.preventDefault();if(busy)return;setBusy(true);setError('');setFields({});const form=new FormData(event.currentTarget)
  const body:FacilityInput=facilityFrom(form,location)
  try{const saved=await api<FacilityProfile>(facility?`/owner/facilities/${facility.id}`:'/owner/facilities',{method:facility?'PUT':'POST',body:JSON.stringify(body)});onSaved?.();router.push(redirectPath??`/owner/facilities/${saved.id}`)}
  catch(err){setError(err instanceof Error?err.message:'Không thể lưu cơ sở.');if(err instanceof ApiError)setFields(Object.fromEntries(err.fieldErrors.map(f=>[f.field,f.message])))}finally{setBusy(false)}
 }
 return <form onSubmit={save} className="grid min-w-0 grid-cols-1 gap-5 rounded-2xl border bg-white p-6 md:grid-cols-2"><FacilityFields facility={facility} location={location} onLocationChange={setLocation} busy={busy} frozen={frozen} fields={fields}/>
  {error&&<p role="alert" className="text-red-700 md:col-span-2">{error}</p>}
  <div className="flex gap-3 md:col-span-2"><Button disabled={busy} className="bg-emerald-600 text-white" type="submit">{busy?'Đang lưu…':facility?'Lưu thay đổi':'Tạo cơ sở'}</Button><Button type="button" variant="outline" onClick={()=>router.back()} disabled={busy}>Quay lại</Button></div>
 </form>
}
