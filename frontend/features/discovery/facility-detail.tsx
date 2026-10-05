'use client'
import Image from 'next/image'
import Link from 'next/link'
import { useApi } from '@/lib/use-api'
import type { Facility,Court,Media,Category } from '@/features/facility/types'
export function PublicFacilityDetail({id}:{id:string}){
 const resource=useApi<Facility>(`/facilities/${id}`);const courts=useApi<Court[]>(`/facilities/${id}/courts`);const images=useApi<Media[]>(`/facilities/${id}/images`);const categories=useApi<Category[]>('/sport-categories')
 if(resource.loading)return <p className="p-8" role="status">Đang tải cơ sở…</p>
 if(resource.error||!resource.data)return <div className="p-8" role="alert">{resource.error}<Link className="ml-4 text-emerald-700 underline" href="/search">Quay lại tìm sân</Link></div>
 const f=resource.data
 return <div className="mx-auto max-w-6xl px-5 py-8"><Link href="/search" className="text-sm text-slate-500 hover:underline">Tìm sân / {f.name}</Link><h1 className="mt-6 text-3xl font-black">{f.name}</h1><p className="mt-3 text-slate-500">{f.addressLine}, {f.ward}, {f.district}, {f.province}</p>
  {images.data&&images.data.length>0&&<div className="mt-6 grid gap-3 sm:grid-cols-3">{images.data.map(i=><Image unoptimized src={i.url} alt={`Ảnh ${f.name}`} key={i.id} width={480} height={320} className="h-52 w-full rounded-xl object-cover"/>)}</div>}{images.error&&<p role="alert" className="mt-4 text-sm text-red-700">Không tải được ảnh: {images.error}</p>}
  <div className="my-8 grid gap-8 md:grid-cols-[1fr_280px]"><div><h2 className="text-xl font-bold">Về cơ sở</h2><p className="mt-3 leading-relaxed text-slate-600">{f.description||'Cơ sở chưa bổ sung mô tả.'}</p><div className="mt-5 flex flex-wrap gap-2">{f.amenities.map(a=><span key={a} className="rounded-full bg-emerald-50 px-4 py-2 text-sm text-emerald-800">{a}</span>)}</div></div><aside className="rounded-2xl bg-slate-50 p-5"><h3 className="font-bold">Thông tin liên hệ</h3><p className="mt-3 text-sm">{f.phone}</p><p className="mt-2 text-sm text-slate-500">Lịch và giờ chơi theo {f.timezone}.</p></aside></div>
  <h2 className="text-2xl font-bold">Chọn sân</h2>{courts.loading&&<p role="status" className="mt-5">Đang tải sân…</p>}{courts.error&&<p role="alert" className="mt-5 text-red-700">{courts.error}</p>}{courts.data?.length===0&&<p className="mt-5 text-slate-500">Hiện chưa có sân đang nhận đặt chỗ.</p>}
  <div className="mt-5 grid gap-4 sm:grid-cols-2">{courts.data?.map(c=><article className="rounded-xl border p-5" key={c.id}><p className="text-xs font-semibold uppercase tracking-wide text-emerald-700">{categories.data?.find(v=>v.id===c.sportCategoryId)?.name||'Sân thể thao'}</p><h3 className="mt-2 text-xl font-bold">{c.name}</h3><p className="mt-2 text-sm text-slate-500">{c.description||c.code}</p><Link href={`/facilities/${id}/courts/${c.id}`} className="mt-5 inline-flex rounded-full bg-emerald-600 px-5 py-2 text-sm text-white">Kiểm tra lịch trống</Link></article>)}</div>
 </div>
}
