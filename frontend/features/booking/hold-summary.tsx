'use client'
import Link from 'next/link'
import { useState } from 'react'
import { useRouter,useSearchParams } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import type { Facility,Court } from '@/features/facility/types'
import { money } from '@/features/schedule/types'
import type { Hold,Booking,Payment } from './types'
import { Countdown,useRemaining } from './countdown'
import type { Group } from '@/features/groups/types'
export function HoldSummary({id}:{id:string}){return <RequireAuth><Loaded id={id}/></RequireAuth>}
function Loaded({id}:{id:string}){
 const router=useRouter();const params=useSearchParams();const counter=params.get('counter')==='true';const resource=useApi<Hold>(`/bookings/holds/${id}`)
 const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [key]=useState(()=>crypto.randomUUID());const [paymentKey]=useState(()=>crypto.randomUUID());const [booking,setBooking]=useState<Booking|null>(null)
 const [mode,setMode]=useState('INDIVIDUAL')
 const remaining=useRemaining(resource.data?.expiresAt)
 const facility=useApi<Facility>(resource.data?`/facilities/${resource.data.facilityId}`:null);const courts=useApi<Court[]>(resource.data?`/facilities/${resource.data.facilityId}/courts`:null)
 async function confirm(e:React.FormEvent<HTMLFormElement>){e.preventDefault();if(busy||!resource.data)return;setBusy(true);setError('');const values=new FormData(e.currentTarget)
  try{if(mode==='GROUP'&&!counter&&!booking){const g=await api<Group>('/groups',{method:'POST',headers:{'Idempotency-Key':key},body:JSON.stringify({holdId:id,name:values.get('groupName'),maxMembers:Number(values.get('maxMembers'))})});router.push(`/customer/groups/${g.id}`);return}const b=booking||await api<Booking>(`/bookings${counter?'?counter=true':''}`,{method:'POST',headers:{'Idempotency-Key':key},body:JSON.stringify({holdId:id,guestName:counter?values.get('guestName'):null,guestPhone:counter?values.get('guestPhone'):null})});setBooking(b);const p=await api<Payment>('/payments',{method:'POST',headers:{'Idempotency-Key':paymentKey},body:JSON.stringify({bookingId:b.id})});router.push(`/customer/payments/${p.id}`)}catch(err){setError(err instanceof Error?err.message:'Không thể tạo booking.')}finally{setBusy(false)}
 }
 async function cancel(){if(!window.confirm('Hủy giữ chỗ này?'))return;setBusy(true);setError('');try{if(booking)await api(`/bookings/${booking.id}/cancel`,{method:'POST',body:JSON.stringify({reason:'User cancelled before payment'})});else await api(`/bookings/holds/${id}`,{method:'DELETE'});router.push('/search')}catch(err){setError(err instanceof Error?err.message:'Không thể hủy giữ chỗ.')}finally{setBusy(false)}}
 if(resource.loading)return <p className="p-8" role="status">Đang tải giữ chỗ…</p>
 if(resource.error||!resource.data)return <div role="alert" className="p-8 text-red-700">{resource.error}<Link className="ml-4 underline" href="/search">Chọn lại sân</Link></div>
 const h=resource.data;const expired=remaining===0||h.state!=='HOLD';const timezone=h.quote.timezone
 return <div className="mx-auto max-w-2xl px-5 py-10"><p className="text-sm font-semibold text-emerald-700">Chọn sân → Tóm tắt → Thanh toán</p><h1 className="mt-4 text-3xl font-black">Kiểm tra lượt đặt</h1><div className="my-6"><Countdown expiresAt={h.expiresAt}/></div><section className="space-y-4 rounded-2xl border p-6"><h2 className="text-xl font-bold">{facility.data?.name||'Cơ sở'} · {courts.data?.find(c=>c.id===h.courtId)?.name||'Sân'}</h2><p className="text-sm text-slate-500">{new Date(h.startsAt).toLocaleString('vi-VN',{timeZone:timezone})} → {new Date(h.endsAt).toLocaleTimeString('vi-VN',{timeZone:timezone})}</p><div className="space-y-3 border-y py-4">{h.quote.segments.map(s=><div className="flex justify-between gap-3 text-sm" key={s.startsAt}><span>{new Date(s.startsAt).toLocaleTimeString('vi-VN',{timeZone:timezone,hour:'2-digit',minute:'2-digit'})} · {s.ruleLabel}</span><span>{money(s.amount||0,s.currency)}</span></div>)}</div><div className="flex items-center justify-between"><span>Tổng thanh toán</span><strong className="text-2xl text-emerald-700">{money(h.quote.amount,h.quote.currency)}</strong></div><p className="text-xs text-slate-500">Thanh toán toàn bộ qua môi trường demo. Hủy trước thanh toán sẽ giải phóng giữ chỗ. Hủy sau thanh toán cần policy hoàn tiền được chốt.</p></section>
  <form onSubmit={confirm} className="mt-6 space-y-4">{!counter&&!booking&&<label className="grid gap-2 text-sm">Hình thức đặt sân<select className="rounded-lg border p-3" value={mode} onChange={e=>setMode(e.target.value)} disabled={busy}><option value="INDIVIDUAL">Cá nhân · thanh toán toàn bộ</option><option value="GROUP">Theo nhóm · chia tiền</option></select></label>}{mode==='GROUP'&&!counter&&<div className="space-y-4 rounded-xl bg-emerald-50 p-5"><label className="grid gap-2 text-sm">Tên nhóm<Input name="groupName" maxLength={180} placeholder="Nhóm cuối tuần" disabled={busy}/></label><label className="grid gap-2 text-sm">Số thành viên tối đa<Input name="maxMembers" type="number" min={1} max={50} defaultValue={10} required disabled={busy}/></label><p className="text-xs text-slate-600">Nhóm có hạn thanh toán chung. Khi đủ 100% tiền sân, booking mới được xác nhận. Nếu hết hạn, sân được giải phóng; khoản đã đóng được lưu để đối soát theo chính sách hoàn tiền.</p></div>}{counter&&<div className="grid gap-4 sm:grid-cols-2"><label className="grid gap-2 text-sm">Tên khách<Input name="guestName" required maxLength={180} disabled={busy||Boolean(booking)}/></label><label className="grid gap-2 text-sm">Số điện thoại khách<Input name="guestPhone" required pattern="\+?[0-9]{8,15}" disabled={busy||Boolean(booking)}/></label></div>}{error&&<p role="alert" className="rounded-lg bg-red-50 p-4 text-sm text-red-700">{error}</p>}<Button className="h-12 w-full bg-emerald-600 text-white" type="submit" disabled={busy||expired}>{busy?'Đang xác nhận…':booking?'Tiếp tục thanh toán':mode==='GROUP'?'Tạo booking nhóm':'Xác nhận và thanh toán'}</Button><Button className="w-full" variant="outline" type="button" disabled={busy} onClick={cancel}>Hủy giữ chỗ</Button></form>
 </div>
}
