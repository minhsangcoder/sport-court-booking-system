'use client'
import { useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import type { TransferPolicy } from '@/features/transfers/types'
import { useFacility } from './facility-context'
export function TransferPolicyPage(){const {facility}=useFacility();const r=useApi<TransferPolicy>(`/owner/facilities/${facility.id}/transfer-policy`);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [saved,setSaved]=useState(false)
 async function submit(e:React.FormEvent<HTMLFormElement>){e.preventDefault();setBusy(true);setError('');setSaved(false);const f=new FormData(e.currentTarget);try{await api(`/owner/facilities/${facility.id}/transfer-policy`,{method:'PUT',body:JSON.stringify({enabled:f.get('enabled')==='on',minLeadSeconds:Math.round(Number(f.get('minutes'))*60)})});r.reload();setSaved(true)}catch(err){setError(err instanceof Error?err.message:'Không thể lưu chính sách.')}finally{setBusy(false)}}
 if(r.loading)return <p role="status">Đang tải cấu hình…</p>;if(!r.data)return <p role="alert" className="text-red-700">{r.error}</p>
 return <section className="max-w-xl"><h2 className="text-2xl font-bold">Điều kiện chuyển nhượng</h2><p className="mt-3 text-slate-500">Áp dụng riêng cho booking tại cơ sở này. Khách chỉ được đăng và nhận lượt đặt trước khoảng thời gian tối thiểu mà bạn chọn.</p>{!r.data.configured&&<p className="mt-5 rounded-xl bg-amber-50 p-4 text-sm text-amber-900">Cơ sở chưa cấu hình; chuyển nhượng tạm chưa được mở.</p>}<form key={JSON.stringify(r.data)} onSubmit={submit} className="mt-6 space-y-5"><label className="flex items-center gap-3 text-sm"><input name="enabled" type="checkbox" defaultChecked={r.data.enabled} disabled={busy}/>Cho phép chuyển nhượng booking đã thanh toán</label><label className="grid gap-2 text-sm">Thời gian tối thiểu trước giờ chơi (phút)<Input name="minutes" type="number" min={0} max={129600} step={1} required defaultValue={r.data.minLeadSeconds/60} disabled={busy}/></label>{error&&<p role="alert" className="text-red-700">{error}</p>}{saved&&<p role="status" className="text-emerald-700">Đã lưu cấu hình cơ sở.</p>}<Button type="submit" className="bg-emerald-600 text-white" disabled={busy}>Lưu điều kiện chuyển nhượng</Button></form></section>
}
