import { Suspense } from 'react'
import { CounterSlots } from '@/features/operations/counter-slots'
export default async function Page({params}:{params:Promise<{courtId:string}>}){const {courtId}=await params;return <Suspense fallback={<p className="p-8">Đang tải…</p>}><CounterSlots courtId={courtId}/></Suspense>}
