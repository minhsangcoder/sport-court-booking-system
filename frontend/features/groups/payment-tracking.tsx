'use client'
import { useRef,useState } from 'react'
import { api } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import { type Group,type ReminderResult,memberStates } from './types'

const reasons:Record<string,string>={PAID:'Đã đủ tiền hoặc không cần đóng',COOLDOWN:'Vừa được nhắc; vui lòng chờ',NOT_MEMBER:'Không còn trong nhóm',SELF:'Không gửi nhắc cho chính mình',WINDOW_CLOSED:'Đã đóng thời hạn góp tiền',NO_ALLOCATION:'Chưa phân chia chi phí'}
export function GroupPaymentTracking({group:g,userId,busy,pay,reload}:{group:Group;userId?:string;busy:boolean;pay:(id:string)=>void;reload:()=>void}){
 const owner=userId===g.ownerId
 const [filter,setFilter]=useState('ALL')
 const [selected,setSelected]=useState<string[]>([])
 const [confirmation,setConfirmation]=useState<{ids:string[];all:boolean}|null>(null)
 const [sending,setSending]=useState(false)
 const flight=useRef(false)
 const [error,setError]=useState('')
 const [result,setResult]=useState<ReminderResult|null>(null)
 const eligible=g.members.filter(m=>m.reminderEligible)
 const selectedEligible=selected.filter(id=>eligible.some(m=>m.id===id))
 const recipients=confirmation?g.members.filter(m=>confirmation.ids.includes(m.id)):[]
 const date=(value:string)=>new Date(value).toLocaleString('vi-VN',{timeZone:g.booking.priceSnapshot.timezone})
 function confirm(all:boolean){setError('');setResult(null);setConfirmation({ids:all?eligible.map(m=>m.id):selectedEligible,all})}
 async function send(){
  if(!confirmation||flight.current)return
  flight.current=true;setSending(true);setError('');setResult(null)
  try{
   const response=await api<ReminderResult>(`/groups/${g.id}/payment-reminders`,{method:'POST',body:JSON.stringify(confirmation.all?{remindAll:true}:{memberIds:confirmation.ids})})
   setResult(response);setSelected([]);setConfirmation(null);reload()
  }catch(err){setError(err instanceof Error?err.message:'Không thể gửi lời nhắc. Vui lòng thử lại.');reload()}
  finally{flight.current=false;setSending(false)}
 }
 const shown=g.members.filter(m=>filter==='ALL'||(filter==='PAID'?m.amountPaid>=m.amountDue&&g.allocationsLocked:m.amountPaid<m.amountDue))
 return <section className="mt-8 min-w-0" aria-label="Theo dõi thanh toán nhóm">
  <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-xl font-bold">Thành viên · {g.members.length}/{g.maxMembers}</h2><Button variant="outline" disabled={sending} onClick={reload}>Tải lại thanh toán</Button></div>
  <p className="mt-3 text-sm text-slate-600">Còn thiếu {money(Math.max(0,g.booking.amount-g.totalPaid),g.booking.currency)} · Hạn góp tiền: {date(g.deadline)}</p>
  <label className="mt-4 block text-sm">Tình trạng thanh toán<select value={filter} onChange={e=>setFilter(e.target.value)} className="ml-2 max-w-full rounded-lg border bg-white p-2"><option value="ALL">Tất cả</option><option value="UNPAID">Chưa đủ tiền</option><option value="PAID">Đã đủ tiền</option></select></label>
  {owner&&g.state==='GROUP_PENDING'&&g.allocationsLocked&&<div className="mt-4 flex flex-wrap gap-3">
   <Button variant="outline" disabled={busy||sending||selectedEligible.length===0} onClick={()=>confirm(false)}>Nhắc thanh toán ({selectedEligible.length})</Button>
   <Button variant="outline" disabled={busy||sending||eligible.length===0} onClick={()=>confirm(true)}>Nhắc tất cả chưa thanh toán</Button>
  </div>}
  {error&&<p role="alert" className="mt-4 rounded-xl bg-red-50 p-4 text-sm text-red-700">{error} {confirmation&&'Bạn có thể bấm xác nhận để thử lại.'}</p>}
  {result&&<div role="status" className="mt-4 rounded-xl bg-emerald-50 p-4 text-sm text-emerald-900"><p>Đã nhận {result.acceptedCount} lời nhắc vào hàng đợi thông báo. Bỏ qua {result.skippedCount} thành viên.</p>{result.members.filter(m=>m.outcome==='SKIPPED').map(m=><p className="mt-2 break-words" key={m.memberId}>{g.members.find(x=>x.id===m.memberId)?.displayName||'Thành viên'}: {reasons[m.reason||'']||'Không còn cần nhắc'}{m.reason==='COOLDOWN'&&m.nextReminderAt&&` · Có thể nhắc lại từ ${date(m.nextReminderAt)}`}</p>)}</div>}
  {confirmation&&<div className="mt-4 rounded-2xl border border-amber-200 bg-amber-50 p-5" role="region" aria-label="Xác nhận nhắc thanh toán">
   <h3 className="font-bold">Xác nhận gửi nhắc thanh toán</h3><p className="mt-2 text-sm">Hạn góp tiền: {date(g.deadline)}. Hệ thống kiểm tra lại số tiền và trạng thái trước khi nhận lời nhắc.</p>
   <ul className="mt-3 space-y-2">{recipients.map(m=><li className="break-words text-sm" key={m.id}>{m.displayName} · Còn {money(Math.max(0,m.amountDue-m.amountPaid),g.booking.currency)}</li>)}</ul>
   {confirmation.all&&<p className="mt-3 text-xs">Danh sách tất cả sẽ được xác định lại trên máy chủ khi gửi.</p>}
   <div className="mt-4 flex flex-wrap gap-3"><Button className="bg-emerald-600 text-white" disabled={sending||busy} onClick={send}>{sending?'Đang gửi…':'Xác nhận gửi nhắc'}</Button><Button variant="outline" disabled={sending} onClick={()=>setConfirmation(null)}>Hủy gửi nhắc</Button></div>
  </div>}
  <div className="mt-4 divide-y rounded-2xl border">{shown.map(m=><div className="flex min-w-0 flex-wrap items-start justify-between gap-4 p-5" key={m.id}>
   <div className="min-w-0 flex-1 basis-48"><p className="break-words font-semibold">{m.displayName} {m.userId===g.ownerId&&<span className="ml-2 text-xs text-emerald-700">Người tổ chức</span>}</p>
    <p className="mt-1 text-sm text-slate-500">{g.allocationsLocked&&m.amountDue===0?'Không cần đóng':memberStates[m.paymentState]} · {money(m.amountPaid,g.booking.currency)} / {money(m.amountDue,g.booking.currency)}</p>
    <p className="mt-1 text-sm">Còn {money(Math.max(0,m.amountDue-m.amountPaid),g.booking.currency)}</p>
    {m.lastReminderAt&&<p className="mt-2 text-xs text-slate-500">Nhắc gần nhất (đã nhận vào hàng đợi): {date(m.lastReminderAt)}</p>}
    {owner&&m.reminderBlockedReason==='COOLDOWN'&&m.nextReminderAt&&<p className="mt-2 text-xs text-amber-800">Có thể nhắc lại từ {date(m.nextReminderAt)}</p>}
    {owner&&m.userId!==g.ownerId&&m.reminderBlockedReason==='WINDOW_CLOSED'&&<p className="mt-2 text-xs text-slate-500">Đã đóng thời hạn góp tiền</p>}
   </div>
   <div className="flex flex-wrap items-center gap-3">
    {owner&&m.userId!==g.ownerId&&g.state==='GROUP_PENDING'&&g.allocationsLocked&&<label className="flex items-center gap-2 text-sm"><input type="checkbox" aria-label={`Chọn nhắc ${m.displayName}`} checked={selectedEligible.includes(m.id)} disabled={!m.reminderEligible||sending||busy} onChange={e=>setSelected(ids=>e.target.checked?[...ids,m.id]:ids.filter(id=>id!==m.id))}/>Chọn nhắc</label>}
    {g.state==='GROUP_PENDING'&&g.allocationsLocked&&m.amountDue>0&&m.amountPaid===0&&(owner||m.userId===userId)&&<Button disabled={busy||sending} onClick={()=>pay(m.id)} className="bg-emerald-600 text-white">{m.userId===userId?'Đóng phần của tôi':'Thanh toán thay'}</Button>}
   </div>
  </div>)}{shown.length===0&&<p className="p-5 text-sm text-slate-500">Không có thành viên phù hợp.</p>}</div>
 </section>
}
