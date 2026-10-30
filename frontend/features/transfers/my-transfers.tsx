'use client'
import { PageBanner, bannerGhost } from '@/components/page-banner'
import Link from 'next/link'
import { useState } from 'react'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { money } from '@/features/schedule/types'
import { type Listing, type Acquisition, transferStates } from './types'
import { AdmissionNotice } from '@/features/booking/admission-notice'
import { useAuth } from '@/lib/auth-context'
import { MessagesSquare } from 'lucide-react'
export function MyTransfers() {
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded />
    </RequireAuth>
  )
}
function Loaded() {
  const { user } = useAuth()
  const [tab, setTab] = useState('listings')
  const listings = useApi<Listing[]>('/transfers/mine')
  const purchases = useApi<Acquisition[]>('/transfers/acquisitions')
  const r = tab === 'listings' ? listings : purchases
  return (
    <>
      <PageBanner
        eyebrow="Chuyển nhượng P2P"
        title="Chuyển nhượng của tôi"
        description="Tin đã đăng và các giao dịch bạn đang tham gia."
        icon="shuttle"
        actions={
          <Link href="/transfers" className={bannerGhost}>
            Mở chợ chuyển nhượng
          </Link>
        }
      />
      <div className="mx-auto max-w-5xl px-5 py-10">
        <AdmissionNotice market />
        <div
          role="tablist"
          aria-label="Giao dịch chuyển nhượng"
          className="mt-6 flex gap-3 border-b pb-4"
        >
          {[
            ['listings', 'Tin đã đăng'],
            ['purchases', 'Giao dịch & trao đổi'],
          ].map(([value, label]) => (
            <button
              role="tab"
              aria-selected={tab === value}
              key={value}
              onClick={() => setTab(value)}
              className={`rounded-full px-5 py-2 text-sm ${tab === value ? 'bg-emerald-600 text-white' : 'bg-slate-100'}`}
            >
              {label}
            </button>
          ))}
        </div>
        {r.loading && (
          <p role="status" className="mt-6">
            Đang tải giao dịch…
          </p>
        )}
        {r.error && (
          <p role="alert" className="mt-6 text-red-700">
            {r.error}
          </p>
        )}
        {r.data?.length === 0 && (
          <p className="mt-6 rounded-2xl border p-6 text-slate-500">
            Chưa có giao dịch. Đăng tin từ chi tiết lượt đặt đã thanh toán hoặc chọn lượt đặt trên
            chợ.
          </p>
        )}
        <div className="mt-6 space-y-4">
          {tab === 'listings'
            ? listings.data?.map((l) => (
                <Link
                  key={l.id}
                  href={`/transfers/${l.id}`}
                  className="flex flex-wrap justify-between gap-4 rounded-2xl border p-5"
                >
                  <div>
                    <h2 className="font-bold">
                      {l.venue.facilityName} · {l.venue.courtName}
                    </h2>
                    <p className="mt-2 text-sm text-slate-500">
                      {new Date(l.startsAt).toLocaleString('vi-VN', { timeZone: l.venue.timezone })}
                    </p>
                  </div>
                  <div className="text-right">
                    <p className="font-bold text-emerald-700">{money(l.price, l.currency)}</p>
                    <p className="mt-2 text-sm">{transferStates[l.state]}</p>
                  </div>
                </Link>
              ))
            : purchases.data?.map((a) => (
                <Link
                  key={a.id}
                  href={`/customer/transfers/acquisitions/${a.id}`}
                  className="flex justify-between gap-4 rounded-2xl border p-5"
                >
                  <span>
                    Giao dịch {a.id.slice(0, 8).toUpperCase()}
                    <span
                      className={`ml-2 rounded-full px-2.5 py-0.5 text-xs font-bold ${a.buyerId === user?.id ? 'bg-sky-50 text-sky-700' : 'bg-emerald-50 text-emerald-700'}`}
                    >
                      {a.buyerId === user?.id ? 'Bạn mua' : 'Bạn bán'}
                    </span>
                    <p className="mt-2 inline-flex items-center gap-1.5 text-sm text-slate-500">
                      {transferStates[a.state]} · <MessagesSquare className="size-3.5" /> Mở trao
                      đổi
                    </p>
                  </span>
                  <strong className="text-emerald-700">{money(a.amount, a.currency)}</strong>
                </Link>
              ))}
        </div>
      </div>
    </>
  )
}
