'use client'
import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { api,ApiError } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { useFacility } from './facility-context'
import type { Court,Category } from './types'
export function CourtsPage(){
 const {facility,basePath}=useFacility();const {data,error,loading,reload}=useApi<Court[]>(`/owner/facilities/${facility.id}/courts`)
 return <section><div className="flex items-center justify-between gap-4"><h2 className="text-2xl font-bold">Danh sách sân</h2><Link className="rounded-full bg-emerald-600 px-5 py-2 text-sm font-semibold text-white" href={`${basePath}/courts/new`}>Thêm sân</Link></div>
  {loading&&<p role="status" className="mt-6">Đang tải sân…</p>}{error&&<p role="alert" className="mt-6 text-red-700">{error} <button onClick={reload}>Thử lại</button></p>}
  {data?.length===0&&<p className="mt-6 rounded-xl border border-dashed p-8 text-slate-500">Chưa có sân. Thêm sân đầu tiên cho cơ sở này.</p>}
  <div className="mt-6 grid gap-4 md:grid-cols-2">{data?.map(c=><article key={c.id} className="rounded-xl border p-5"><div className="flex justify-between"><h3 className="font-bold">{c.name}</h3><span className={`text-xs ${c.enabled?'text-emerald-700':'text-slate-500'}`}>{c.enabled?'Đang bật':'Đã tắt'}</span></div><p className="mt-2 text-sm text-slate-500">Mã sân: {c.code}</p><p className="mt-3 text-sm">{c.description}</p><Link className="mt-4 inline-block text-sm font-semibold text-emerald-700" href={`${basePath}/courts/${c.id}/edit`}>Chỉnh sửa sân →</Link></article>)}</div>
 </section>
}
export function CourtEditor({courtId}:{courtId?:string}){
 const court=useApi<Court>(courtId?`/owner/courts/${courtId}`:null)
 if(court.loading)return <p role="status">Đang tải sân…</p>
 if(court.error)return <p role="alert" className="text-red-700">{court.error}</p>
 return <CourtForm court={court.data??undefined}/>
}
function CourtForm({court}:{court?:Court}){
 const {facility,basePath}=useFacility();const router=useRouter();const categories=useApi<Category[]>('/sport-categories');const [busy,setBusy]=useState(false);const [error,setError]=useState('')
 const [fields,setFields]=useState<Record<string,string>>({})
 async function save(event:React.FormEvent<HTMLFormElement>){event.preventDefault();if(busy)return;setBusy(true);setError('');setFields({});const form=new FormData(event.currentTarget)
  try{await api<Court>(court?`/owner/courts/${court.id}`:`/owner/facilities/${facility.id}/courts`,{method:court?'PUT':'POST',body:JSON.stringify({code:String(form.get('code')).trim(),name:String(form.get('name')).trim(),sportCategoryId:form.get('sportCategoryId'),description:form.get('description'),enabled:form.get('enabled')==='on'})});router.push(`${basePath}/courts`)}
  catch(err){setError(err instanceof Error?err.message:'Không thể lưu sân.');if(err instanceof ApiError)setFields(Object.fromEntries(err.fieldErrors.map(f=>[f.field,f.message])))}finally{setBusy(false)}
 }
 return <section><h2 className="mb-6 text-2xl font-bold">{court?'Chỉnh sửa sân':'Thêm sân mới'}</h2><form className="grid max-w-2xl gap-5 rounded-xl border p-6" onSubmit={save}>
  <label className="grid gap-2 text-sm">Mã sân<Input name="code" defaultValue={court?.code} required maxLength={50} disabled={busy}/>{fields.code&&<span className="text-red-700">{fields.code}</span>}</label>
  <label className="grid gap-2 text-sm">Tên sân<Input name="name" defaultValue={court?.name} required maxLength={180} disabled={busy}/>{fields.name&&<span className="text-red-700">{fields.name}</span>}</label>
  <label className="grid gap-2 text-sm">Môn thể thao<select name="sportCategoryId" className="h-10 rounded-lg border bg-white px-3" defaultValue={court?.sportCategoryId??''} required disabled={busy||categories.loading}><option value="">Chọn môn thể thao</option>{categories.data?.filter(c=>c.active||c.id===court?.sportCategoryId).map(c=><option key={c.id} value={c.id}>{c.name}{!c.active?' (ngừng tạo mới)':''}</option>)}</select></label>
  {categories.loading&&<p role="status">Đang tải danh mục…</p>}{categories.error&&<p role="alert" className="text-red-700">{categories.error}</p>}{categories.data?.length===0&&<p>Danh mục môn thể thao đang trống; Admin cần tạo danh mục trước.</p>}
  <label className="grid gap-2 text-sm">Mô tả<textarea name="description" className="min-h-24 rounded-lg border p-3" defaultValue={court?.description} maxLength={5000} disabled={busy}/></label>
  <label className="flex gap-3 text-sm"><input name="enabled" type="checkbox" defaultChecked={court?.enabled??true} disabled={busy}/>Bật sân để vận hành</label>
  {error&&<p role="alert" className="text-red-700">{error}</p>}<Button type="submit" disabled={busy||categories.loading} className="bg-emerald-600 text-white">{busy?'Đang lưu…':'Lưu sân'}</Button>
 </form></section>
}
