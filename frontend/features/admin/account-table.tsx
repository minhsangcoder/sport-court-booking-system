'use client'
import type { useAccounts } from '@/features/admin/use-accounts'
import { displayLabel } from '@/lib/display-labels'
import { ArrowRight, CheckCircle, RefreshCw, Users } from 'lucide-react'
import Link from 'next/link'
export function AccountTable({ model }: { model: Pick<ReturnType<typeof useAccounts>, 'r'> }) {
  const { r } = model
  return (
    <div className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-center justify-between border-b border-slate-100 p-4 dark:border-slate-800">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-bold text-slate-900 dark:text-white">Danh sách tài khoản</h3>
          {r.data && (
            <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-bold text-slate-600 dark:bg-slate-800 dark:text-slate-300">
              {r.data.length}
            </span>
          )}
        </div>
        <p className="text-xs text-slate-400">Tối đa 100 kết quả gần nhất</p>
      </div>

      {/* Loading State */}
      {r.loading && (
        <div className="p-12 text-center text-xs text-slate-400">
          <RefreshCw className="mx-auto size-6 animate-spin text-emerald-600" />
          <p className="mt-2 font-semibold">Đang truy vấn dữ liệu tài khoản…</p>
        </div>
      )}

      {/* Error State */}
      {r.error && (
        <div role="alert" className="p-6 text-center text-xs text-red-600">
          <p className="font-bold">Không thể tải danh sách tài khoản.</p>
          <p className="mt-1">{r.error}</p>
          <button onClick={r.reload} className="mt-3 font-bold underline">
            Thử tải lại
          </button>
        </div>
      )}

      {/* Empty State */}
      {!r.loading && r.data?.length === 0 && (
        <div className="p-12 text-center">
          <Users className="mx-auto size-10 text-slate-300" />
          <h4 className="mt-3 text-sm font-bold text-slate-800 dark:text-slate-200">
            Không tìm thấy tài khoản phù hợp
          </h4>
          <p className="mt-1 text-xs text-slate-400">
            Hãy thử nới lỏng từ khóa hoặc bộ lọc trạng thái.
          </p>
        </div>
      )}

      {/* Data Table */}
      {!r.loading && r.data && r.data.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-slate-100 bg-slate-50/70 text-slate-400 dark:border-slate-800 dark:bg-slate-800/40">
                <th className="py-3 px-4 font-bold uppercase tracking-wider">Người dùng</th>
                <th className="py-3 px-4 font-bold uppercase tracking-wider">Liên hệ & Xác minh</th>
                <th className="py-3 px-4 font-bold uppercase tracking-wider">Vai trò</th>
                <th className="py-3 px-4 font-bold uppercase tracking-wider">Trạng thái</th>
                <th className="py-3 px-4 font-bold uppercase tracking-wider">Ngày đăng ký</th>
                <th className="py-3 px-4 font-bold uppercase tracking-wider text-right">
                  Thao tác
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {r.data.map((u) => {
                const initial = u.fullName ? u.fullName.charAt(0).toUpperCase() : 'U'
                return (
                  <tr
                    key={u.id}
                    className="hover:bg-slate-50/80 transition dark:hover:bg-slate-800/50"
                  >
                    {/* Name & Avatar */}
                    <td className="py-3.5 px-4">
                      <div className="flex items-center gap-3">
                        <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-slate-100 text-xs font-bold text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                          {initial}
                        </div>
                        <div>
                          <p className="font-bold text-slate-900 dark:text-white">{u.fullName}</p>
                          <p className="text-[10px] font-mono text-slate-400 truncate max-w-[120px]">
                            {u.id}
                          </p>
                        </div>
                      </div>
                    </td>

                    {/* Contact & Verified badges */}
                    <td className="py-3.5 px-4">
                      <p className="font-medium text-slate-700 dark:text-slate-300">
                        {u.email || u.phone || 'Chưa cập nhật'}
                      </p>
                      <div className="mt-1 flex items-center gap-2 text-[10px]">
                        {u.emailVerified && (
                          <span className="flex items-center gap-1 text-emerald-600 dark:text-emerald-400 font-semibold">
                            <CheckCircle size={10} />
                            <span>Đã xác minh thư điện tử</span>
                          </span>
                        )}
                        {u.phoneVerified && (
                          <span className="flex items-center gap-1 text-emerald-600 dark:text-emerald-400 font-semibold">
                            <CheckCircle size={10} />
                            <span>Đã xác minh số điện thoại</span>
                          </span>
                        )}
                      </div>
                    </td>

                    {/* Roles */}
                    <td className="py-3.5 px-4">
                      <div className="flex flex-wrap gap-1">
                        {u.roles.map((role) => (
                          <span
                            key={role}
                            className={`rounded-md px-2 py-0.5 text-[10px] font-bold ${
                              role === 'ADMIN'
                                ? 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
                                : role === 'OWNER'
                                  ? 'bg-purple-100 text-purple-800 dark:bg-purple-950 dark:text-purple-300'
                                  : role === 'STAFF'
                                    ? 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300'
                                    : 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'
                            }`}
                          >
                            {displayLabel(role)}
                          </span>
                        ))}
                      </div>
                    </td>

                    {/* Status */}
                    <td className="py-3.5 px-4">
                      <span
                        className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[10px] font-bold ${
                          u.status === 'ACTIVE'
                            ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-400'
                            : u.status === 'LOCKED'
                              ? 'bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-400'
                              : 'bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-400'
                        }`}
                      >
                        <span
                          className={`size-1.5 rounded-full ${
                            u.status === 'ACTIVE'
                              ? 'bg-emerald-500'
                              : u.status === 'LOCKED'
                                ? 'bg-red-500'
                                : 'bg-amber-500'
                          }`}
                        />
                        <span>{displayLabel(u.status)}</span>
                      </span>
                    </td>

                    {/* Created At */}
                    <td className="py-3.5 px-4 text-slate-500">
                      {new Date(u.createdAt).toLocaleDateString('vi-VN')}
                    </td>

                    {/* Actions */}
                    <td className="py-3.5 px-4 text-right">
                      <Link
                        href={`/admin/accounts/${u.id}`}
                        className="inline-flex items-center gap-1 rounded-full border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-bold text-slate-700 shadow-sm hover:border-emerald-500 hover:text-emerald-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
                      >
                        <span>Quản trị</span>
                        <ArrowRight size={12} />
                      </Link>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
