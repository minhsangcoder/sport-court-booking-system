'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { refreshSession } from '@/rebuilt/lib/api'
import { consumeGoogleNext } from './google-auth-state'

export function GoogleAuthCallback() {
  const router = useRouter()
  const [error, setError] = useState(false)

  useEffect(() => {
    let active = true
    void refreshSession().then(() => {
      if (!active) return
      router.replace(consumeGoogleNext())
    }).catch(() => {
      if (active) setError(true)
    })
    return () => { active = false }
  }, [router])

  return error
    ? <main className="mx-auto max-w-md px-5 py-16 text-center">
        <p role="alert" className="text-red-700">Không thể hoàn tất đăng nhập Google. Vui lòng thử lại.</p>
        <Link className="mt-4 inline-block text-emerald-700 underline" href="/login">Quay lại đăng nhập</Link>
      </main>
    : <main className="px-5 py-16 text-center" role="status">Đang hoàn tất đăng nhập…</main>
}
