'use client'
import { card, primaryPill, textLink } from './owner-ui'
import type { useFacilityOverview } from '@/features/facility/use-facility-overview'
import { Layers, PlusCircle } from 'lucide-react'
import Link from 'next/link'
export function OverviewCourts({
  model,
}: {
  model: Pick<ReturnType<typeof useFacilityOverview>, 'basePath' | 'courts'>
}) {
  const { basePath, courts } = model
  return (
    <div className={`${card} home-rise p-5 sm:p-6`}>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
            Danh sách sân trực thuộc
          </h3>
          <p className="text-xs text-slate-500">Các sân đang được cấu hình trong cơ sở</p>
        </div>
        <Link href={`${basePath}/courts`} className={textLink}>
          Quản lý chi tiết →
        </Link>
      </div>

      {courts.loading && <p className="text-xs text-slate-400 py-4">Đang tải danh sách sân…</p>}

      {!courts.loading && courts.data?.length === 0 && (
        <div className="rounded-2xl border border-dashed border-slate-300 p-8 text-center dark:border-slate-700">
          <Layers className="mx-auto size-8 text-slate-400" />
          <p className="mt-2 text-sm font-bold text-slate-700 dark:text-slate-300">
            Chưa có sân nào trong cơ sở
          </p>
          <p className="text-xs text-slate-500 mt-1">
            Hãy thêm sân đầu tiên để khách có thể đặt lịch.
          </p>
          <Link href={`${basePath}/courts/new`} className={`${primaryPill} mt-4`}>
            <PlusCircle size={14} />
            <span>Thêm sân ngay</span>
          </Link>
        </div>
      )}

      {!courts.loading && courts.data && courts.data.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-slate-400 dark:border-slate-800">
                <th className="py-2.5 text-xs font-semibold uppercase tracking-wider">Mã sân</th>
                <th className="py-2.5 text-xs font-semibold uppercase tracking-wider">
                  Tên sân đấu
                </th>
                <th className="py-2.5 text-xs font-semibold uppercase tracking-wider">
                  Trạng thái
                </th>
                <th className="py-2.5 text-xs font-semibold uppercase tracking-wider text-right">
                  Thao tác
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {courts.data.map((c) => (
                <tr key={c.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/50">
                  <td className="py-3 font-mono font-bold text-slate-600 dark:text-slate-300">
                    {c.code}
                  </td>
                  <td className="py-3 font-bold text-slate-900 dark:text-white">{c.name}</td>
                  <td className="py-3">
                    <span
                      className={`inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold ${
                        c.enabled
                          ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-400'
                          : 'bg-slate-100 text-slate-500 dark:bg-slate-800'
                      }`}
                    >
                      <span
                        className={`size-1.5 rounded-full ${c.enabled ? 'bg-emerald-500' : 'bg-slate-400'}`}
                      />
                      <span>{c.enabled ? 'Đang bật' : 'Tạm tắt'}</span>
                    </span>
                  </td>
                  <td className="py-3 text-right">
                    <Link href={`${basePath}/courts/${c.id}/edit`} className={textLink}>
                      Chỉnh sửa
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
