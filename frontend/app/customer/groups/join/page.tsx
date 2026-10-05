import { Suspense } from 'react'
import { JoinPage } from '@/features/groups/join-page'
export default function Page(){return <Suspense fallback={<p className="p-8">Đang tải lời mời…</p>}><JoinPage/></Suspense>}
