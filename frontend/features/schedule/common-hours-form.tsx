'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { useHoursEditor } from '@/features/schedule/use-hours-editor'
export function CommonHoursForm({
  model,
}: {
  model: Pick<
    ReturnType<typeof useHoursEditor>,
    'courtId' | 'initial' | 'ranges' | 'busy' | 'removeRange' | 'updateRange' | 'addRange'
  >
}) {
  const { courtId, initial, ranges, busy, removeRange, updateRange, addRange } = model
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-3 dark:border-slate-800">
        <div>
          <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
            Các khoảng mở cửa chung (Toàn bộ tuần)
          </h3>
          <p className="mt-0.5 text-xs text-slate-500">
            Khung giờ mở cửa áp dụng mặc định cho các ngày trong tuần (Thứ hai – Chủ nhật). Các
            khung giờ ngoài khoảng này mặc định đóng cửa.
          </p>
        </div>
        <span className="inline-flex items-center rounded-full bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700 border border-emerald-200">
          Cả tuần (Thứ 2 – CN)
        </span>
      </div>

      {courtId && initial.length === 0 && (
        <div className="rounded-2xl bg-emerald-50 border border-emerald-200 p-3 text-xs text-emerald-800">
          Sân này hiện đang kế thừa lịch mở cửa của toàn cơ sở. Bạn có thể chỉnh sửa và nhấn{' '}
          <strong>&quot;Lưu lịch&quot;</strong> để đặt lịch riêng cho sân này.
        </div>
      )}

      <div className="space-y-3">
        {ranges.map((row, i) => (
          <div
            key={i}
            className="rounded-2xl border border-slate-200/80 bg-slate-50/70 p-4 dark:border-slate-800 dark:bg-slate-800/40"
          >
            <div className="mb-3 flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-700">
                {i === 0
                  ? 'Khoảng thời gian 1 (Ca chính)'
                  : 'Khoảng thời gian 2 (Tùy chọn - Ca phụ)'}
              </span>
              {i > 0 && (
                <button
                  type="button"
                  className="text-xs font-medium text-red-600 hover:text-red-700 underline"
                  disabled={busy}
                  onClick={() => removeRange(i)}
                >
                  Bỏ khoảng
                </button>
              )}
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="grid gap-1.5 text-xs font-medium text-slate-700 dark:text-slate-300">
                Mở cửa
                <Input
                  type="time"
                  value={row.opensAt}
                  onChange={(e) => updateRange(i, 'opensAt', e.target.value)}
                  required
                  disabled={busy}
                />
              </label>
              <label className="grid gap-1.5 text-xs font-medium text-slate-700 dark:text-slate-300">
                Đóng cửa
                <Input
                  type="time"
                  value={row.closesAt}
                  onChange={(e) => updateRange(i, 'closesAt', e.target.value)}
                  required
                  disabled={busy}
                />
              </label>
            </div>
          </div>
        ))}
      </div>

      {ranges.length < 2 && (
        <Button type="button" variant="outline" disabled={busy} onClick={addRange}>
          Thêm khoảng giờ
        </Button>
      )}
    </div>
  )
}
