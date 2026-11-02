'use client'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { BannerPage } from '@/components/page-banner'
import { Button } from '@/components/ui/button'
import { RequireAuth } from '@/features/auth/require-auth'
import { useApi } from '@/lib/use-api'
import Image from 'next/image'
import Link from 'next/link'
import { useState } from 'react'
import type { Invite } from './types'
export function InvitePage({ id }: { id: string }) {
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded id={id} />
    </RequireAuth>
  )
}
function Loaded({ id }: { id: string }) {
  const r = useApi<Invite>(`/groups/${id}/invite`)
  const [message, setMessage] = useState('')
  async function copy() {
    if (!r.data) return
    try {
      await navigator.clipboard.writeText(`${window.location.origin}${r.data.path}`)
      setMessage('Đã sao chép liên kết mời.')
    } catch {
      setMessage('Không thể sao chép; chọn và sao chép mã bên dưới.')
    }
  }
  return (
    <BannerPage
      eyebrow={
        <Link className={bannerLink} href={`/customer/groups/${id}`}>
          Nhóm / Mời thành viên
        </Link>
      }
      title="Mời bạn cùng chơi"
      width="max-w-xl"
    >
      {r.loading && (
        <p className="mt-6" role="status">
          Đang tạo lời mời…
        </p>
      )}
      {r.error && (
        <p className="mt-6 text-red-700" role="alert">
          {r.error}
        </p>
      )}
      {r.data && (
        <section className="mt-6 space-y-5 p-6 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          <div className="flex justify-center">
            <Image
              unoptimized
              alt="QR mời tham gia nhóm"
              src={`data:image/svg+xml,${encodeURIComponent(r.data.qrSvg)}`}
              width={280}
              height={280}
            />
          </div>
          <p className="text-sm text-slate-500">
            Lời mời tự hết hạn lúc {new Date(r.data.expiresAt).toLocaleString('vi-VN')}.
          </p>
          <Button className="w-full bg-emerald-600 text-white" onClick={copy}>
            Sao chép liên kết mời
          </Button>
          <details className="text-sm">
            <summary>Mã mời để nhập thủ công</summary>
            <p className="mt-3 break-all rounded-2xl bg-slate-50 p-3 font-mono text-xs">
              {r.data.code}
            </p>
          </details>
          <a
            className="block text-center text-sm font-semibold text-emerald-700 hover:underline"
            download={`sporthub-group-${id}.${r.data.qrPngDataUrl ? 'png' : 'svg'}`}
            href={r.data.qrPngDataUrl || `data:image/svg+xml,${encodeURIComponent(r.data.qrSvg)}`}
          >
            Tải mã QR
          </a>
          {message && (
            <p role="status" className="text-sm">
              {message}
            </p>
          )}
        </section>
      )}
    </BannerPage>
  )
}
