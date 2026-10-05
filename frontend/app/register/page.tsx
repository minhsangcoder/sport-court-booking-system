import { Suspense } from 'react'
import { AuthForm } from '@/features/auth/auth-form'
export default function Page() { return <div className="px-5 py-12 md:py-20"><Suspense fallback={<p>Đang tải…</p>}><AuthForm mode="register" /></Suspense></div> }
