'use client'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Court } from '@/features/facility/types'
export function CounterPage(){const params=useSearchParams();const facility=params.get('facilityId');const courts=useApi<Court[]>(facility?`/facilities/${facility}/courts`:null);return <RequireAuth roles={['STAFF']}><div className="mx-auto max-w-4xl px-5 py-10"><Link className="text-sm text-slate-500" href="/staff/bookings">Vận hành / Đặt tại quầy</Link><h1 className="mt-6 text-3xl font-black">Chọn sân cho khách</h1>{!facility&&<p className="mt-4">Chọn cơ sở tại màn hình vận hành trước.</p>}{courts.loading&&<p role="status">Đang tải sân…</p>}{courts.error&&<p role="alert" className="text-red-700">{courts.error}</p>}{courts.data?.length===0&&<p className="mt-4 text-slate-500">Chưa có sân đang nhận đặt.</p>}<div className="mt-6 grid gap-4 sm:grid-cols-2">{courts.data?.map(c=><Link key={c.id} href={`/staff/counter-booking/${c.id}?facilityId=${facility}`} className="rounded-xl border p-5 font-semibold hover:border-emerald-500">{c.name}</Link>)}</div></div></RequireAuth>}
