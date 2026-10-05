import { Suspense } from 'react'
import { CounterPage } from '@/features/operations/counter-page'
export default function Page(){return <Suspense fallback={<p className="p-8">Đang tải…</p>}><CounterPage/></Suspense>}
