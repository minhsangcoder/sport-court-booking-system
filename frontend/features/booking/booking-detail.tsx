'use client'
import Image from 'next/image'
import Link from 'next/link'
import { useEffect,useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import type { Facility,Court } from '@/features/facility/types'
import { type BookingDetail,type Payment,statusLabels } from './types'
import { Countdown } from './countdown'
export function BookingDetailPage({id}:{id:string}){return <RequireAuth><Loaded id={id}/></RequireAuth>}
function Loaded({id}:{id:string}){
 const router=useRouter();const resource=useApi<BookingDetail>(`/bookings/${id}`);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const b=resource.data?.booking
 const facility=useApi<Facility>(b?`/facilities/${b.facilityId}`:null);const courts=useApi<Court[]>(b?`/facilities/${b.facilityId}/courts`:null)
 useEffect(()=>{if(b?.status!=='CONFIRMED')return;const timer=setInterval(resource.reload,60000);return()=>clearInterval(timer)},[b?.status,resource.reload])
 async function pay(){if(busy)return;setBusy(true);setError('');try{const p=await api<Payment>('/payments',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({bookingId:id})});router.push(`/customer/payments/${p.id}`)}catch(err){setError(err instanceof Error?err.message:'Không thể tạo thanh toán.')}finally{setBusy(false)}}
 async function cancel(){if(!window.confirm('Hủy booking chưa thanh toán và giải phóng slot?'))return;setBusy(true);setError('');try{await api(`/bookings/${id}/cancel`,{method:'POST',body:JSON.stringify({reason:'User cancelled before payment'})});resource.reload()}catch(err){setError(err instanceof Error?err.message:'Không thể hủy booking.')}finally{setBusy(false)}}
 if(resource.loading)return <p role="status" className="p-8">Đang tải booking…</p>
 if(resource.error||!b)return <p role="alert" className="p-8 text-red-700">{resource.error}</p>
 const detail=resource.data!;const timezone=b.priceSnapshot.timezone
 return <div className="mx-auto max-w-4xl px-5 py-10"><Link className="text-sm text-slate-500" href="/customer/bookings">Lịch đặt / {id.slice(0,8).toUpperCase()}</Link><div className="my-6 flex flex-wrap items-center justify-between gap-3"><h1 className="text-3xl font-black">{statusLabels[b.status]||b.status}</h1><span className="rounded-full bg-emerald-50 px-4 py-2 text-sm text-emerald-700">{b.currency} · {money(b.amount,b.currency)}</span></div><section className="rounded-2xl border p-6"><h2 className="text-xl font-bold">{facility.data?.name||'Cơ sở'} · {courts.data?.find(c=>c.id===b.courtId)?.name||'Sân'}</h2><p className="mt-3 text-sm text-slate-500">{new Date(b.startsAt).toLocaleString('vi-VN',{timeZone:timezone})} → {new Date(b.endsAt).toLocaleTimeString('vi-VN',{timeZone:timezone})}</p><p className="mt-2 break-all text-xs text-slate-400">Mã booking {id}</p><div className="mt-5 space-y-2 border-t pt-4">{b.priceSnapshot.segments.map(s=><div className="flex justify-between text-sm" key={s.startsAt}><span>{s.ruleLabel}</span><span>{money(s.amount||0,s.currency)}</span></div>)}</div></section>
  {b.status==='PENDING'&&<div className="mt-6 space-y-4"><Countdown expiresAt={b.holdExpiresAt}/><div className="flex flex-wrap gap-3"><Button onClick={pay} disabled={busy} className="bg-emerald-600 text-white">Thanh toán</Button><Button onClick={cancel} disabled={busy} variant="outline">Hủy booking</Button></div></div>}
  {detail.checkinToken&&<section className="mt-6 rounded-2xl bg-emerald-50 p-6"><h2 className="text-xl font-bold">Mã check-in của bạn</h2><p className="mt-3 text-sm text-emerald-900">Mã tự hết hạn sau 2 phút. Mở trang khi đến sân để nhận mã mới.</p>{detail.checkinQrSvg&&<Image unoptimized src={`data:image/svg+xml,${encodeURIComponent(detail.checkinQrSvg)}`} width={240} height={240} alt="QR check-in booking" className="mt-5 rounded-xl bg-white p-3"/>}<details className="mt-4 text-sm"><summary>Mã dùng khi nhập thủ công</summary><p className="mt-3 break-all rounded-lg bg-white p-3 font-mono text-xs">{detail.checkinToken}</p></details><button onClick={resource.reload} className="mt-4 text-sm text-emerald-700 underline">Làm mới mã</button></section>}
  {error&&<p role="alert" className="mt-5 text-red-700">{error}</p>}<section className="mt-8"><h2 className="text-xl font-bold">Lịch sử booking</h2><ol className="mt-5 space-y-4 border-l-2 border-emerald-100 pl-5">{detail.history.map(h=><li key={h.id}><p className="text-sm font-semibold">{statusLabels[h.action]||h.action}</p><p className="mt-1 text-xs text-slate-500">{new Date(h.createdAt).toLocaleString('vi-VN')}</p></li>)}</ol></section>
 </div>
}
