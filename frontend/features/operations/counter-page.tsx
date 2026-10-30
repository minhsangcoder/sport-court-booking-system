'use client'
import { PageBanner, bannerGhost } from '@/components/page-banner'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Court, Facility } from '@/features/facility/types'
import { useApi } from '@/lib/use-api'
import { Building2, ChevronLeft } from 'lucide-react'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { useState } from 'react'

export function CounterPage({ owner = false }: { owner?: boolean }) {
  const params = useSearchParams()
  const owned = useApi<Facility[]>(owner ? '/owner/facilities' : null)
  const [selection, setSelection] = useState(params.get('facilityId') || '')
  const facility = owner
    ? owned.data?.find((f) => f.id === selection)?.id || owned.data?.[0]?.id
    : params.get('facilityId')
  const workspace = owner ? 'owner' : 'staff'
  const courts = useApi<Court[]>(facility ? `/facilities/${facility}/courts` : null)

  return (
    <RequireAuth roles={[owner ? 'OWNER' : 'STAFF']}>
      <div>
        <PageBanner
          eyebrow="Vận hành · Đặt tại quầy"
          title="Chọn sân cho khách"
          description="Tạo lượt đặt hộ khách có tài khoản hoặc khách vãng lai. Người thao tác không trở thành chủ lượt đặt."
          icon="tennis"
          actions={
            <Link
              className={bannerGhost}
              href={`/${workspace}/bookings${facility ? `?facilityId=${facility}` : ''}`}
            >
              <ChevronLeft size={16} /> Vận hành / Đặt tại quầy
            </Link>
          }
        >
          <div className="flex flex-wrap gap-2.5 text-xs text-emerald-100">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-400/40 bg-emerald-500/25 px-3.5 py-1.5 font-semibold text-emerald-200 shadow-xs backdrop-blur-md">
              <span className="size-1.5 rounded-full bg-emerald-400" />
              Bước 1: Chọn sân
            </span>
            <span className="rounded-full border border-white/10 bg-white/5 px-3.5 py-1.5 text-slate-300 backdrop-blur-md">
              Bước 2: Chọn khung giờ
            </span>
            <span className="rounded-full border border-white/10 bg-white/5 px-3.5 py-1.5 text-slate-300 backdrop-blur-md">
              Bước 3: Xác nhận & Thu tiền
            </span>
          </div>
        </PageBanner>

        <div className="home-rise mx-auto max-w-5xl space-y-6 px-4 py-8 sm:px-6 sm:py-10">
          {owner && owned.data && owned.data.length > 0 && (
            <div className="rounded-2xl border border-slate-200/80 bg-white/90 p-5 shadow-xs backdrop-blur-sm dark:border-slate-800 dark:bg-slate-900/90">
              <label className="grid gap-2 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
                <span className="flex items-center gap-2">
                  <Building2 size={16} className="text-emerald-600" /> Cơ sở
                </span>
                <select
                  className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                  value={facility || ''}
                  onChange={(e) => setSelection(e.target.value)}
                >
                  {owned.data.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.name}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          )}

          {owned.loading && (
            <p role="status" className="text-sm font-medium text-slate-500">
              Đang tải cơ sở…
            </p>
          )}

          {owned.error && (
            <p
              role="alert"
              className="rounded-2xl border border-red-200 bg-red-50 p-4 text-xs font-medium text-red-700 shadow-2xs dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
            >
              {owned.error}
            </p>
          )}

          {!facility && (
            <div className="rounded-2xl border border-amber-200 bg-amber-50/70 p-5 text-sm font-medium text-amber-800 shadow-2xs">
              Chọn cơ sở tại màn hình vận hành trước.
            </div>
          )}

          {courts.loading && (
            <p role="status" className="text-sm font-medium text-slate-500">
              Đang tải sân…
            </p>
          )}

          {courts.error && (
            <p
              role="alert"
              className="rounded-2xl border border-red-200 bg-red-50 p-4 text-xs font-medium text-red-700 shadow-2xs dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
            >
              {courts.error}
            </p>
          )}

          {courts.data?.length === 0 && (
            <div className="rounded-2xl border border-slate-200/80 bg-white p-8 text-center text-sm font-medium text-slate-500 shadow-2xs dark:border-slate-800 dark:bg-slate-900">
              Chưa có sân đang nhận đặt.
            </div>
          )}

          <div className="grid gap-4 sm:grid-cols-2">
            {courts.data?.map((c) => (
              <Link
                key={c.id}
                href={`/${workspace}/counter-booking/${c.id}?facilityId=${facility}`}
                className="court-card-hover group relative flex items-center justify-between rounded-2xl border border-slate-200/90 bg-white p-5 font-bold text-slate-800 shadow-2xs hover:border-emerald-500 hover:text-emerald-700 hover:shadow-md transition-all duration-200 dark:border-slate-800 dark:bg-slate-900 dark:text-white dark:hover:border-emerald-500"
              >
                {c.name}
              </Link>
            ))}
          </div>
        </div>
      </div>
    </RequireAuth>
  )
}
