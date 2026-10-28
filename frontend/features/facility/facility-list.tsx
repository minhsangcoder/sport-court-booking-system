'use client'
import { RequireAuth } from '@/features/auth/require-auth'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
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
    <div className="mx-auto max-w-6xl px-5 py-6 sm:py-8">
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-4">
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.14em] text-emerald-700">
            Không gian chủ cơ sở
          </p>
          <h1 className="mt-1 text-xl sm:text-2xl font-bold tracking-tight text-slate-900">
            Cơ sở của tôi
          </h1>
        </div>
        <Link
          className="inline-flex h-9 items-center rounded-lg bg-emerald-700 px-4 text-xs sm:text-[13px] font-semibold text-white shadow-2xs hover:bg-emerald-800 transition-colors"
          href="/owner/facilities/new"
        >
          Tạo cơ sở
        </Link>
      </div>
      {loading && (
        <p role="status" className="mt-6 text-xs text-slate-500">
          Đang tải cơ sở…
        </p>
      )}
      {error && (
        <div
          role="alert"
          className="mt-6 rounded-lg border border-red-200 bg-red-50 p-4 text-xs text-red-700"
        >
          {error}
          <button className="ml-3 font-semibold underline" onClick={reload}>
            Thử lại
          </button>
        </div>
      )}
      {data?.length === 0 && (
        <div className="mt-6 rounded-xl border border-dashed border-slate-300 bg-white p-8 text-center">
          <h2 className="text-base font-semibold text-slate-900">Chưa có cơ sở</h2>
          <p className="mt-1.5 text-xs text-slate-500">
            Tạo hồ sơ cơ sở đầu tiên để bắt đầu quản lý sân.
          </p>
        </div>
      )}
      <div className="mt-6 grid gap-4 md:grid-cols-2">
        {data?.map((f) => (
          <Link
            key={f.id}
            href={`/owner/facilities/${f.id}`}
            className="rounded-xl border border-slate-200 bg-white p-5 shadow-2xs transition hover:border-slate-300 hover:shadow-sm"
          >
            <span className="inline-flex rounded-md border border-emerald-200 bg-emerald-50 px-2.5 py-0.5 text-[11px] font-semibold text-emerald-800">
              {displayLabel(f.status)}
            </span>
            <h2 className="mt-3 text-base font-semibold text-slate-900">{f.name}</h2>
            <p className="mt-1 text-xs text-slate-500">
              {f.addressLine}, {f.ward}, {f.province}
            </p>
            <p className="mt-4 text-xs font-semibold text-emerald-700">Quản lý cơ sở →</p>
          </Link>
        ))}
      </div>
    </div>
  )
}
