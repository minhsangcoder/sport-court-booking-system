'use client'
import { useState } from 'react'
import { useRouter,useSearchParams } from 'next/navigation'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import type { Facility,Court } from '@/features/facility/types'
import { RequireAuth } from '@/features/auth/require-auth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { money } from '@/features/schedule/types'
import type { Group } from './types'
export function JoinPage(){return <RequireAuth roles={['CUSTOMER']}><Loaded/></RequireAuth>}
function Loaded(){const router=useRouter();const params=useSearchParams();const [code,setCode]=useState(params.get('code')||'');const [group,setGroup]=useState<Group|null>(null);const [busy,setBusy]=useState(false);const [error,setError]=useState('')
 const facility=useApi<Facility>(group?`/facilities/${group.booking.facilityId}`:null);const courts=useApi<Court[]>(group?`/facilities/${group.booking.facilityId}/courts`:null)
 async function preview(e:React.FormEvent<HTMLFormElement>){e.preventDefault();setBusy(true);setError('');try{setGroup(await api<Group>(`/groups/invitations?code=${encodeURIComponent(code.trim())}`))}catch(err){setError(err instanceof Error?err.message:'Mã mời không hợp lệ.');setGroup(null)}finally{setBusy(false)}}
 async function join(){setBusy(true);setError('');try{const g=await api<Group>('/groups/join',{method:'POST',body:JSON.stringify({code:code.trim()})});router.push(`/customer/groups/${g.id}`)}catch(err){setError(err instanceof Error?err.message:'Không thể tham gia.')}finally{setBusy(false)}}
 return <div className="mx-auto max-w-xl px-5 py-10"><h1 className="text-3xl font-black">Tham gia nhóm đặt sân</h1><form onSubmit={preview} className="mt-6 space-y-4"><label className="grid gap-2 text-sm">Mã mời nhóm<Input value={code} onChange={e=>{setCode(e.target.value);setGroup(null)}} required maxLength={500} disabled={busy}/></label><Button type="submit" className="w-full bg-emerald-600 text-white" disabled={busy}>Kiểm tra lời mời</Button></form>{error&&<p className="mt-5 text-sm text-red-700" role="alert">{error}</p>}{group&&<section className="mt-6 space-y-4 rounded-2xl border p-6"><h2 className="text-xl font-bold">{group.name}</h2><p className="font-semibold">{facility.data?.name} · {courts.data?.find(c=>c.id===group.booking.courtId)?.name}</p><p className="text-sm text-slate-500">{facility.data?.addressLine}, {facility.data?.district}, {facility.data?.province}</p><p className="text-sm">Người tổ chức: {group.members.find(m=>m.userId===group.ownerId)?.displayName}</p><p>{new Date(group.booking.startsAt).toLocaleString('vi-VN',{timeZone:group.booking.priceSnapshot.timezone})}</p><p>{group.members.length}/{group.maxMembers} thành viên · Tổng {money(group.booking.amount)}</p><p className="text-sm text-slate-500">Hạn thanh toán: {new Date(group.deadline).toLocaleString('vi-VN')}. Bạn sẽ đóng phần chi phí sau khi người tổ chức chốt danh sách.</p><Button className="w-full bg-emerald-600 text-white" onClick={join} disabled={busy||group.allocationsLocked||group.members.length>=group.maxMembers}>Xác nhận tham gia</Button></section>}</div>
}
