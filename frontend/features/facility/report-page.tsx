'use client'
import { useState } from 'react'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import { reportRange } from '@/features/admin/report-page'
import { statusLabels } from '@/features/booking/types'
import { useFacility } from './facility-context'
import type { Court } from './types'
type Report={courts:{court_id:string;status:string;count:number;booking_value:number;reserved_minutes:number}[]}
export function FacilityReport(){const {facility}=useFacility();const [query,setQuery]=useState('');const r=useApi<Report>(`/bookings/reports/facility/${facility.id}?${query}`);const courts=useApi<Court[]>(`/owner/facilities/${facility.id}/courts`)
 return <section><h2 className="text-2xl font-bold">Báo cáo vận hành cơ sở</h2><form onSubmit={e=>{e.preventDefault();setQuery(reportRange(new FormData(e.currentTarget)))}} className="mt-5 flex flex-wrap items-end gap-4 rounded-2xl bg-slate-50 p-5"><label className="grid gap-2 text-sm">Từ ngày<Input name="from" type="date"/></label><label className="grid gap-2 text-sm">Đến hết ngày<Input name="to" type="date"/></label><Button type="submit" className="bg-emerald-600 text-white">Xem báo cáo</Button></form><p className="mt-4 text-sm text-slate-500">Booking được tính theo giờ bắt đầu chơi. Giá trị booking là giá gốc tại lúc đặt; chưa phải doanh thu đã tất toán.</p>{r.loading&&<p className="mt-6" role="status">Đang tổng hợp dữ liệu…</p>}{r.error&&<p className="mt-6 text-red-700" role="alert">{r.error}<button className="ml-4 underline" onClick={r.reload}>Thử lại</button></p>}{r.data?.courts.length===0&&<p className="mt-6 rounded-xl border p-6 text-slate-500">Chưa có booking trong khoảng thời gian này.</p>}<div className="mt-6 grid gap-4 md:grid-cols-2">{r.data?.courts.map(x=><article key={`${x.court_id}-${x.status}`} className="rounded-2xl border p-5"><h3 className="font-bold">{courts.data?.find(c=>c.id===x.court_id)?.name||x.court_id}</h3><p className="mt-2 text-sm text-emerald-700">{statusLabels[x.status]}</p><p className="mt-4">{x.count} booking · {x.reserved_minutes} phút trong lịch</p><p className="mt-3 text-sm text-slate-500">Giá trị gốc: {money(x.booking_value)}</p></article>)}</div></section>
}
