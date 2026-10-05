'use client'
import Link from 'next/link'
import { useEffect,useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import { Countdown,useRemaining } from '@/features/booking/countdown'
import type { Payment } from '@/features/booking/types'
import { type Acquisition,transferStates } from './types'
export function AcquisitionPage({id}:{id:string}){return <RequireAuth roles={['CUSTOMER']}><Loaded id={id}/></RequireAuth>}
function Loaded({id}:{id:string}){const router=useRouter();const r=useApi<Acquisition>(`/transfers/acquisitions/${id}`);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const remaining=useRemaining(r.data?.expiresAt)
 useEffect(()=>{if(!r.data||!['PENDING','PENDING_HANDOFF'].includes(r.data.state))return;const timer=setInterval(r.reload,2000);return()=>clearInterval(timer)},[r.data,r.reload])
 async function pay(){if(!r.data||busy)return;setBusy(true);setError('');try{const p=await api<Payment>('/payments',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({bookingId:r.data.bookingId,acquisitionId:id})});router.push(`/customer/payments/${p.id}`)}catch(err){setError(err instanceof Error?err.message:'Không thể tạo thanh toán.');r.reload()}finally{setBusy(false)}}
 async function cancel(){if(!window.confirm('Hủy giao dịch chưa hoàn tất và mở lại tin cho người khác?'))return;setBusy(true);setError('');try{await api(`/transfers/acquisitions/${id}/cancel`,{method:'POST'});r.reload()}catch(err){setError(err instanceof Error?err.message:'Không thể hủy giao dịch.')}finally{setBusy(false)}}
 if(r.loading)return <p role="status" className="p-8">Đang tải giao dịch…</p>;if(!r.data)return <p role="alert" className="p-8 text-red-700">{r.error}<button className="ml-4 underline" onClick={r.reload}>Thử lại</button></p>;const a=r.data
 return <div className="mx-auto max-w-xl px-5 py-10"><Link href="/customer/transfers" className="text-sm text-slate-500">Chuyển nhượng / Giao dịch</Link><h1 className="mt-6 text-3xl font-black">{transferStates[a.state]}</h1><section className="mt-6 space-y-4 rounded-2xl border p-6"><p className="text-3xl font-bold text-emerald-700">{money(a.amount,a.currency)}</p><p className="break-all text-xs text-slate-500">Mã giao dịch {id}</p><Link className="block text-sm text-emerald-700 underline" href={`/transfers/${a.listingId}`}>Xem sân và khung giờ</Link>{a.state==='PENDING'&&<Countdown expiresAt={a.expiresAt}/>}</section>{(error||r.error)&&<p role="alert" className="mt-5 text-red-700">{error||r.error}</p>}{a.state==='PENDING'&&<div className="mt-6 grid gap-3"><Button className="h-12 bg-emerald-600 text-white" onClick={pay} disabled={busy||remaining===0}>Thanh toán để nhận lượt đặt</Button><Button variant="outline" onClick={cancel} disabled={busy}>Hủy giao dịch</Button></div>}{a.state==='PENDING_HANDOFF'&&<p role="status" className="mt-6 rounded-xl bg-emerald-50 p-5 text-emerald-800">Đã xác minh thanh toán. Đang bàn giao quyền sử dụng và tạo mã check-in mới…</p>}{a.state==='SUCCESS'&&<Link href={`/customer/bookings/${a.bookingId}`} className="mt-6 block rounded-full bg-emerald-600 p-3 text-center text-white">Mở booking và mã check-in của bạn</Link>}{a.state==='PENDING_AUDIT'&&<p className="mt-6 rounded-xl bg-amber-50 p-5 text-sm text-amber-900">Giao dịch đang đối soát. Quyền sử dụng chỉ được cấp sau khi bàn giao thành công.</p>}</div>
}
