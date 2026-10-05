'use client'
import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useAuth } from '@/lib/auth-context'
import { RequireAuth } from '@/features/auth/require-auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import type { Group } from './types'
export function SplitPage({id}:{id:string}){return <RequireAuth roles={['CUSTOMER']}><Loaded id={id}/></RequireAuth>}
function Loaded({id}:{id:string}){const router=useRouter();const {user}=useAuth();const r=useApi<Group>(`/groups/${id}`);const [mode,setMode]=useState('EQUAL');const [amounts,setAmounts]=useState<Record<string,string>>({});const [busy,setBusy]=useState(false);const [error,setError]=useState('')
 async function submit(e:React.FormEvent<HTMLFormElement>){e.preventDefault();if(!r.data)return;setBusy(true);setError('');try{await api(`/groups/${id}/allocations`,{method:'PUT',body:JSON.stringify({mode,allocations:mode==='CUSTOM'?r.data.members.map(m=>({memberId:m.id,amount:Number(amounts[m.id]??m.amountDue)})):null})});router.push(`/customer/groups/${id}`)}catch(err){setError(err instanceof Error?err.message:'Không thể chia tiền.')}finally{setBusy(false)}}
 if(r.loading)return <p className="p-8" role="status">Đang tải phân chia…</p>
 if(!r.data)return <p className="p-8 text-red-700" role="alert">{r.error}</p>
 const g=r.data;const sum=g.members.reduce((v,m)=>v+Number(amounts[m.id]??m.amountDue),0);const base=Math.floor(g.booking.amount/g.members.length);const remainder=g.booking.amount-base*g.members.length;const locked=g.members.some(m=>m.amountPaid>0||m.paymentRequestedAt!==null)||g.state!=='GROUP_PENDING'
 if(g.ownerId!==user?.id)return <p className="p-8" role="alert">Chỉ người tổ chức được chia tiền nhóm.</p>
 return <div className="mx-auto max-w-2xl px-5 py-10"><Link className="text-sm text-slate-500" href={`/customer/groups/${id}`}>{g.name} / Phân chia chi phí</Link><h1 className="mt-5 text-3xl font-black">Chốt danh sách và chia tiền</h1><p className="mt-3 text-slate-500">Tổng tiền giữ nguyên: {money(g.booking.amount)}. Phần dư chia đều được cộng cho người tổ chức.</p><form className="mt-6 space-y-5" onSubmit={submit}><label className="grid gap-2 text-sm">Cách chia<select className="rounded-lg border p-3" value={mode} onChange={e=>setMode(e.target.value)} disabled={busy||locked}><option value="EQUAL">Chia đều</option><option value="CUSTOM">Tùy chỉnh từng người</option></select></label><div className="divide-y rounded-2xl border">{g.members.map(m=><label key={m.id} className="flex items-center justify-between gap-4 p-5"><span>{m.displayName}</span>{mode==='CUSTOM'?<Input className="w-40" type="number" min={0} step={1} required value={amounts[m.id]??m.amountDue} onChange={e=>setAmounts(v=>({...v,[m.id]:e.target.value}))} disabled={busy||locked}/>:<strong>{money(base+(m.userId===g.ownerId?remainder:0))}</strong>}</label>)}</div>{mode==='CUSTOM'&&<p className={sum===g.booking.amount?'text-emerald-700':'text-red-700'}>Tổng phân bổ: {money(sum)} · Chênh lệch: {money(sum-g.booking.amount)}</p>}{(error||locked)&&<p role="alert" className="rounded-lg bg-amber-50 p-4 text-sm text-amber-800">{error||'Không thể thay đổi phân bổ khi thanh toán đã bắt đầu hoặc nhóm đã kết thúc.'}</p>}<Button type="submit" className="h-12 w-full bg-emerald-600 text-white" disabled={busy||locked||(mode==='CUSTOM'&&sum!==g.booking.amount)}>Xác nhận phân chia và khóa danh sách</Button></form></div>
}
