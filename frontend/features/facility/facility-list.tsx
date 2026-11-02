'use client'
import { PageBanner, bannerPrimary } from '@/components/page-banner'
import { RequireAuth } from '@/features/auth/require-auth'
import { PickleBall } from '@/features/home/home-art'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import { ArrowRight, Building2, MapPin, Plus } from 'lucide-react'
import Link from 'next/link'
import { card, outlinePill, primaryPill } from './owner-ui'
import type { Facility } from './types'
export function FacilityListPage() {
  return (
    <RequireAuth roles={['OWNER']}>
      <FacilityList />
    </RequireAuth>
  )
}
function FacilityList() {
  const { data, error, loading, reload } = useApi<Facility[]>('/owner/facilities')
  return (
    <>
      <PageBanner
        eyebrow="Không gian chủ cơ sở"
        title="Cơ sở của tôi"
        description="Quản lý sân, lịch mở cửa, bảng giá và doanh thu của từng cơ sở."
        icon="shuttle"
        actions={
          <Link className={bannerPrimary} href="/owner/facilities/new">
            <Plus className="size-4" strokeWidth={2} />
            Tạo cơ sở
          </Link>
        }
      />
      <div className="mx-auto max-w-6xl px-4 py-7 sm:px-6 sm:py-9">
        {loading && !data && (
          <div role="status" className="grid gap-5 md:grid-cols-2">
            <span className="sr-only">Đang tải cơ sở…</span>
            {[0, 1].map((i) => (
              <div
                key={i}
                className={`${card} h-44 animate-pulse bg-slate-100 dark:bg-slate-800`}
              />
            ))}
          </div>
        )}
        {error && (
          <div role="alert" className={`${card} home-rise p-6`}>
            <p className="text-sm font-semibold text-red-700 dark:text-red-300">{error}</p>
            <button type="button" className={`${outlinePill} mt-4`} onClick={reload}>
              Thử lại
            </button>
          </div>
        )}
        {data?.length === 0 && (
          <section className={`${card} home-rise p-8 text-center sm:p-12`}>
            <PickleBall className="home-float mx-auto w-14" />
            <h2 className="mt-5 text-xl font-extrabold tracking-tight text-slate-950 dark:text-white">
              Chưa có cơ sở
            </h2>
            <p className="mt-2 text-sm text-slate-600 dark:text-slate-400">
              Tạo hồ sơ cơ sở đầu tiên để bắt đầu quản lý sân.
            </p>
            <Link className={`${primaryPill} mt-6`} href="/owner/facilities/new">
              Tạo cơ sở <ArrowRight className="size-4" />
            </Link>
          </section>
        )}
        <ul className="grid gap-5 md:grid-cols-2">
          {data?.map((f, i) => (
            <li
              key={f.id}
              className="home-rise"
              style={{ animationDelay: `${Math.min(i, 8) * 50}ms` }}
            >
              <Link
                href={`/owner/facilities/${f.id}`}
                className={`${card} group flex h-full flex-col p-5 transition hover:-translate-y-0.5 hover:border-emerald-500/50 hover:shadow-2xl sm:p-6`}
              >
                <div className="flex items-start justify-between gap-3">
                  <span className="flex size-12 items-center justify-center rounded-2xl bg-emerald-700 text-white shadow-md">
                    <Building2 size={22} strokeWidth={1.6} />
                  </span>
                  <span
                    className={`rounded-full border px-3 py-1 text-xs font-semibold ${
                      f.status === 'ACTIVE'
                        ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200'
                        : 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200'
                    }`}
                  >
                    {displayLabel(f.status)}
                  </span>
                </div>
                <h2 className="mt-4 text-lg font-bold text-slate-950 transition-colors group-hover:text-emerald-700 dark:text-white dark:group-hover:text-emerald-400">
                  {f.name}
                </h2>
                <p className="mt-1.5 flex items-start gap-1.5 text-sm text-slate-600 dark:text-slate-400">
                  <MapPin className="mt-0.5 size-3.5 shrink-0" />
                  {f.addressLine}, {f.ward}, {f.province}
                </p>
                <p className="mt-auto inline-flex items-center gap-1.5 pt-4 text-sm font-semibold text-emerald-700 dark:text-emerald-400">
                  Quản lý cơ sở
                  <ArrowRight className="size-4 transition group-hover:translate-x-0.5" />
                </p>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </>
  )
}
