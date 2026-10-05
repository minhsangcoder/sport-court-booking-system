import { Suspense } from 'react'
import { HoldSummary } from '@/features/booking/hold-summary'
export default async function Page({params}:{params:Promise<{holdId:string}>}){const {holdId}=await params;return <Suspense fallback={<p className="p-8">Đang tải…</p>}><HoldSummary id={holdId}/></Suspense>}
