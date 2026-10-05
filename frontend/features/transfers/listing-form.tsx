'use client'
import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import type { BookingDetail } from '@/features/booking/types'
import type { Listing } from './types'
export function ListingForm({bookingId,listingId}:{bookingId?:string;listingId?:string}){return <RequireAuth roles={['CUSTOMER']}><Loaded bookingId={bookingId} listingId={listingId}/></RequireAuth>}
function localInput(value:string){const d=new Date(value);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16)}
function Loaded({bookingId,listingId}:{bookingId?:string;listingId?:string}){const router=useRouter();const b=useApi<BookingDetail>(bookingId?`/bookings/${bookingId}`:null);const l=useApi<Listing>(listingId?`/transfers/${listingId}`:null);const [busy,setBusy]=useState(false);const [error,setError]=useState('')
 async function submit(e:React.FormEvent<HTMLFormElement>){e.preventDefault();setBusy(true);setError('');const f=new FormData(e.currentTarget);try{const body={bookingId,price:Number(f.get('price')),deadline:new Date(String(f.get('deadline'))).toISOString()};const result=await api<Listing>(listingId?`/transfers/${listingId}`:'/transfers',{method:listingId?'PUT':'POST',headers:listingId?{}:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify(body)});router.push(`/transfers/${result.id}`)}catch(err){setError(err instanceof Error?err.message:'Không thể đăng tin.')}finally{setBusy(false)}}
 if(b.loading||l.loading)return <p role="status" className="p-8">Đang tải booking…</p>;if(!b.data&&!l.data)return <p role="alert" className="p-8 text-red-700">{b.error||l.error}<button className="ml-4 underline" onClick={()=>{b.reload();l.reload()}}>Thử lại</button></p>;const amount=b.data?.booking.amount??l.data!.originalAmount;const starts=b.data?.booking.startsAt??l.data!.startsAt;const currency=b.data?.booking.currency??l.data!.currency
 return <div className="mx-auto max-w-xl px-5 py-10"><Link href={listingId?`/transfers/${listingId}`:`/customer/bookings/${bookingId}`} className="text-sm text-slate-500">Quay lại chi tiết</Link><h1 className="mt-6 text-3xl font-black">{listingId?'Sửa tin chuyển nhượng':'Đăng lượt đặt lên chợ'}</h1><p className="mt-4 text-slate-500">Giá gốc: {money(amount,currency)}. Sân, giờ chơi và giá gốc của booking được giữ nguyên.</p><form onSubmit={submit} className="mt-8 space-y-5"><label className="grid gap-2 text-sm">Giá chuyển nhượng ({currency})<Input name="price" type="number" min={1} max={amount} step={currency==='VND'?1:0.01} required defaultValue={l.data?.price??amount} disabled={busy}/></label><label className="grid gap-2 text-sm">Hạn nhận lượt đặt<Input name="deadline" type="datetime-local" required max={localInput(starts)} defaultValue={localInput(l.data?.deadline??starts)} disabled={busy}/></label><p className="rounded-xl bg-amber-50 p-4 text-sm text-amber-900">Giá không vượt giá gốc. Owner cơ sở quyết định khoảng thời gian tối thiểu trước giờ chơi. Tất toán tiền cho người bán hiện đang chờ chốt chính sách.</p>{error&&<p role="alert" className="text-red-700">{error}</p>}<Button type="submit" className="h-12 w-full bg-emerald-600 text-white" disabled={busy}>{busy?'Đang lưu…':listingId?'Lưu thay đổi':'Đăng tin chuyển nhượng'}</Button></form></div>
}
