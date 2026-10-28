'use client'
import type { DiscoveryResult } from '@/features/discovery/types'
import { useApi } from '@/lib/use-api'
import { ArrowRight, Building2, Clock, SlidersHorizontal } from 'lucide-react'
import Link from 'next/link'
import { CourtCarousel } from './home-court-carousel'
import { CourtLines, TennisBall } from './home-art'
export function HomeAvailability({ today }: { today: string }) {
  const courtsResource = useApi<{ items: DiscoveryResult[]; truncated: boolean }>(
    `/bookings/search?date=${today}&sort=NAME`,
  )
  return (
    <section className="relative overflow-hidden border-y border-emerald-100 bg-gradient-to-br from-white/80 via-emerald-50/70 to-amber-50/60 py-16 backdrop-blur-sm dark:border-slate-800 dark:bg-slate-900/50 dark:from-transparent dark:via-transparent dark:to-transparent">
      <CourtLines className="pointer-events-none absolute -left-24 top-4 w-[520px] rotate-6 text-emerald-800/[0.07]" />
      <TennisBall className="home-float pointer-events-none absolute right-[5%] top-8 hidden w-12 lg:block" />
      <div className="relative mx-auto max-w-7xl px-4 sm:px-6">
        <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-end">
          <div>
            <div className="inline-flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-emerald-700 dark:text-emerald-400">
              <Clock size={12} />
              <span>Cập nhật theo thời gian thực</span>
            </div>
            <h2 className="mt-1 text-lg font-bold tracking-tight text-slate-900 sm:text-xl dark:text-white">
              Sân thể thao còn lịch trống hôm nay
            </h2>
          </div>
          <Link
            href="/search"
            className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 text-xs font-semibold text-slate-700 shadow-2xs transition-colors hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 w-fit"
          >
            <SlidersHorizontal size={13} />
            <span>Mở bộ lọc tìm kiếm</span>
          </Link>
        </div>

        {/* Loading Skeleton */}
        {courtsResource.loading && (
          <div className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
            {[1, 2, 3].map((n) => (
              <div
                key={n}
                className="animate-pulse rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
              >
                <div className="h-36 w-full rounded-lg bg-slate-100 dark:bg-slate-800" />
                <div className="mt-3 h-4 w-2/3 rounded bg-slate-100 dark:bg-slate-800" />
                <div className="mt-2 h-3 w-1/2 rounded bg-slate-100 dark:bg-slate-800" />
                <div className="mt-4 flex justify-between border-t border-slate-100 pt-3">
                  <div className="h-5 w-1/3 rounded bg-slate-100 dark:bg-slate-800" />
                  <div className="h-8 w-1/4 rounded bg-slate-100 dark:bg-slate-800" />
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Error State */}
        {courtsResource.error && (
          <div
            role="alert"
            className="mt-6 rounded-xl border border-red-200 bg-red-50 p-4 text-xs text-red-700 dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
          >
            <p className="font-semibold">Không thể tải danh sách sân gợi ý.</p>
            <p className="mt-1">{courtsResource.error}</p>
            <button
              type="button"
              onClick={courtsResource.reload}
              className="mt-2 inline-block font-semibold text-red-800 underline dark:text-red-200"
            >
              Tải lại danh sách
            </button>
          </div>
        )}

        {/* Empty State */}
        {!courtsResource.loading && courtsResource.data?.items.length === 0 && (
          <div className="mt-6 flex flex-col items-start justify-between gap-4 rounded-xl border border-slate-200 bg-slate-50/70 p-5 sm:flex-row sm:items-center dark:border-slate-800 dark:bg-slate-900">
            <div className="flex items-start gap-3.5">
              <div className="flex size-9 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-600 shadow-2xs dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300">
                <Building2 size={18} />
              </div>
              <div>
                <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                  Chưa có sân nào còn khung giờ trống cho hôm nay
                </h3>
                <p className="mt-0.5 text-xs text-slate-500">
                  Chọn ngày chơi tiếp theo hoặc mở rộng bộ lọc khu vực để xem toàn bộ lịch sân trên
                  hệ thống.
                </p>
              </div>
            </div>
            <Link
              href="/search"
              className="inline-flex h-9 shrink-0 items-center gap-1.5 rounded-lg bg-emerald-700 px-4 text-xs font-semibold text-white shadow-2xs transition-colors hover:bg-emerald-800"
            >
              <span>Tra cứu lịch các ngày tới</span>
              <ArrowRight size={14} />
            </Link>
          </div>
        )}

        {!courtsResource.loading && courtsResource.data && courtsResource.data.items.length > 0 && (
          <CourtCarousel items={courtsResource.data.items} today={today} />
        )}
      </div>
    </section>
  )
}
