'use client'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useRef,useState,useTransition } from 'react'
import { AdminNav } from '@/features/admin/account-list'
import { useApi } from '@/lib/use-api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Failure } from './application-page'
import { applicationLabels,type OwnerApplicationPage } from './types'
import { applicationQuery,applicationListHref,filterKeys,ownerApplicationListPath,type ApplicationFilters } from './search'

const selectClass='h-10 min-w-0 w-full rounded-lg border bg-white px-3'
export function AdminOwnerApplicationList({initial}:{initial:ApplicationFilters}){
 const router=useRouter(),[pending,startTransition]=useTransition(),[formError,setFormError]=useState('')
 const formRef=useRef<HTMLFormElement>(null)
 const filters:ApplicationFilters={state:'PENDING_APPROVAL',...initial}
 const r=useApi<OwnerApplicationPage>(`/admin/owner-applications/page?${applicationQuery(filters)}`)
 const busy=r.loading||pending,returnTo=applicationListHref(filters)
 function apply(e:React.FormEvent<HTMLFormElement>){
  e.preventDefault();const form=new FormData(e.currentTarget),next:ApplicationFilters={page:'0'}
  for(const key of filterKeys){if(key==='page')continue;const value=String(form.get(key)||'').trim();if(value||key==='state')next[key]=value}
  for(const [from,to] of [['submittedFrom','submittedTo'],['reviewedFrom','reviewedTo']] as const){
   const fromValue=next[from],toValue=next[to]
   if(fromValue&&toValue&&fromValue>toValue){setFormError('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.');return}
  }
  setFormError('');const href=applicationListHref(next)
  if(href===returnTo)r.reload();else startTransition(()=>router.push(href,{scroll:false}))
 }
 function reset(){
  setFormError('')
  if(Object.keys(initial).length===0){formRef.current?.reset();r.reload()}
  else startTransition(()=>router.push(ownerApplicationListPath,{scroll:false}))
 }
 return <main className="mx-auto max-w-6xl px-5 py-10">
  <h1 className="text-3xl font-black">Đăng ký Owner</h1><AdminNav active="owner-applications"/>
  <p className="mt-5 text-sm text-slate-500">Tra cứu hồ sơ và cơ sở đầu tiên. Mặc định ưu tiên hồ sơ chờ thẩm định gửi trước.</p>
  <form ref={formRef} onSubmit={apply} className="mt-6 space-y-5 rounded-2xl bg-slate-50 p-5">
   <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
    <label className="grid gap-2 text-sm">Từ khóa<Input name="q" defaultValue={filters.q} maxLength={180} placeholder="Doanh nghiệp, cơ sở, tài khoản, SĐT"/></label>
    <label className="grid gap-2 text-sm">Trạng thái<select name="state" defaultValue={filters.state} className={selectClass}><option value="">Tất cả</option>{Object.entries(applicationLabels).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label>
    <label className="grid gap-2 text-sm">Tài khoản applicant<Input name="applicant" defaultValue={filters.applicant} maxLength={254} placeholder="Tên tài khoản, email hoặc SĐT"/></label>
    <label className="grid gap-2 text-sm">Tên cơ sở đã đăng ký<Input name="facility" defaultValue={filters.facility} maxLength={180} placeholder="Tên cơ sở đầu tiên"/></label>
   </div>
   <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
    <label className="grid gap-2 text-sm">Gửi từ ngày<Input type="date" name="submittedFrom" defaultValue={filters.submittedFrom}/></label>
    <label className="grid gap-2 text-sm">Gửi đến ngày<Input type="date" name="submittedTo" defaultValue={filters.submittedTo}/></label>
    <label className="grid gap-2 text-sm">Thứ tự<select name="sort" defaultValue={filters.sort||'SUBMITTED_ASC'} className={selectClass}><option value="SUBMITTED_ASC">Gửi trước hiển thị trước</option><option value="SUBMITTED_DESC">Gửi mới nhất hiển thị trước</option></select></label>
    <label className="grid gap-2 text-sm">Hồ sơ mỗi trang<select name="size" defaultValue={filters.size||'20'} className={selectClass}>{[2,10,20,50,100].map(size=><option key={size} value={size}>{size}</option>)}</select></label>
   </div>
   <details className="rounded-xl border bg-white p-4" open={Boolean(filters.reviewedFrom||filters.reviewedTo||filters.reviewedBy||filters.applicationId)}>
    <summary className="cursor-pointer text-sm font-semibold">Bộ lọc thẩm định nâng cao</summary>
    <div className="mt-4 grid gap-4 sm:grid-cols-2">
     <label className="grid gap-2 text-sm">Quyết định từ ngày<Input type="date" name="reviewedFrom" defaultValue={filters.reviewedFrom}/></label>
     <label className="grid gap-2 text-sm">Quyết định đến ngày<Input type="date" name="reviewedTo" defaultValue={filters.reviewedTo}/></label>
     <label className="grid gap-2 text-sm">Mã Admin thẩm định<Input name="reviewedBy" defaultValue={filters.reviewedBy} maxLength={36} placeholder="UUID của Admin"/></label>
     <label className="grid gap-2 text-sm">Mã hồ sơ<Input name="applicationId" defaultValue={filters.applicationId} maxLength={36} placeholder="UUID của hồ sơ"/></label>
    </div>
   </details>
   <p className="text-xs text-slate-500">Các bộ lọc được kết hợp đồng thời. Ngày tính theo giờ Việt Nam (UTC+7), gồm cả ngày kết thúc; ngày gửi là lần gửi gần nhất. Tên đại diện pháp lý mã hóa được xem trong chi tiết hồ sơ.</p>
   <div className="flex flex-wrap gap-3"><Button type="submit" disabled={busy}>Tra cứu hồ sơ</Button><Button type="button" variant="outline" disabled={busy} onClick={reset}>Đặt lại bộ lọc</Button></div>
   {formError&&<p role="alert" className="text-sm text-red-700">{formError}</p>}
  </form>
  {busy&&<p role="status" className="mt-6">Đang tải hồ sơ…</p>}
  {r.error&&<Failure message={r.error} retry={r.reload}/>}
  {!busy&&r.data&&<section aria-label="Kết quả hồ sơ" className="mt-6">
   <p role="status" className="text-sm text-slate-600">{r.data.totalElements} hồ sơ phù hợp{r.data.totalPages>0?(r.data.page<r.data.totalPages?` · Trang ${r.data.page+1}/${r.data.totalPages}`:` · ${r.data.totalPages} trang`):''}</p>
   {r.data.items.length===0&&<div className="mt-4 rounded-xl border border-dashed p-6 text-slate-500"><p>{r.data.totalElements===0?'Không có hồ sơ phù hợp với bộ lọc.':'Trang này không còn hồ sơ. Hãy trở về trang đầu.'}</p>{r.data.page>0&&<Link href={applicationListHref({...filters,page:'0'})} className="mt-3 inline-block text-emerald-700 underline">Về trang đầu</Link>}</div>}
   <div className="mt-4 divide-y rounded-2xl border">{r.data.items.map(a=><Link href={`${ownerApplicationListPath}/${a.id}?${new URLSearchParams({returnTo})}`} key={a.id} className="flex flex-wrap justify-between gap-4 p-6 hover:bg-emerald-50"><div className="min-w-0"><h2 className="break-words font-bold">{a.businessName}</h2><p className="mt-2 break-words text-sm text-slate-500">{a.facilityName}</p>{a.submittedAt&&<p className="mt-2 text-xs text-slate-500">{new Date(a.submittedAt).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'})}</p>}</div><span className="text-sm font-semibold text-emerald-700">{applicationLabels[a.state]}</span></Link>)}</div>
   {r.data.totalPages>1&&<nav aria-label="Phân trang hồ sơ" className="mt-5 flex flex-wrap items-center gap-4">
    {r.data.page>0&&<Link href={applicationListHref({...filters,page:String(r.data.page-1)})} className="rounded-lg border px-4 py-2 text-sm hover:bg-slate-50">Trang trước</Link>}
    {r.data.page+1<r.data.totalPages&&<Link href={applicationListHref({...filters,page:String(r.data.page+1)})} className="rounded-lg border px-4 py-2 text-sm hover:bg-slate-50">Trang sau</Link>}
   </nav>}
  </section>}
 </main>
}
