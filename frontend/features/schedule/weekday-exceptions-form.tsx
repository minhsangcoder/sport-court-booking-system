'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { weekdays } from '@/features/schedule/types'
import type { useHoursEditor } from '@/features/schedule/use-hours-editor'
export function WeekdayExceptionsForm({
  model,
}: {
  model: Pick<
    ReturnType<typeof useHoursEditor>,
    | 'weekdayExceptions'
    | 'busy'
    | 'addWeekdayException'
    | 'updateExceptionDay'
    | 'updateExceptionClosed'
    | 'removeWeekdayException'
    | 'removeExceptionRange'
    | 'updateExceptionRange'
    | 'addExceptionRange'
  >
}) {
  const {
    weekdayExceptions,
    busy,
    addWeekdayException,
    updateExceptionDay,
    updateExceptionClosed,
    removeWeekdayException,
    removeExceptionRange,
    updateExceptionRange,
    addExceptionRange,
  } = model
  return (
    <div className="p-5 space-y-4 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-3">
        <div>
          <h4 className="text-sm font-bold text-slate-900">
            Ngoại lệ giờ mở cửa theo thứ trong tuần
          </h4>
          <p className="mt-0.5 text-xs text-slate-500">
            Cài đặt giờ mở cửa riêng hoặc đóng cửa định kỳ cho một số thứ cụ thể (ví dụ: Thứ bảy,
            Chủ nhật). Thứ có ngoại lệ sẽ được{' '}
            <strong className="text-emerald-700">ưu tiên áp dụng trước</strong> giờ mở cửa chung của
            tuần.
          </p>
        </div>
        {weekdayExceptions.length < 7 && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={addWeekdayException}
            className="text-xs font-semibold text-emerald-700 border-emerald-300 hover:bg-emerald-50"
          >
            + Thêm ngoại lệ theo thứ
          </Button>
        )}
      </div>

      {weekdayExceptions.length === 0 ? (
        <p className="text-xs text-slate-500 py-1 italic">
          Chưa có ngoại lệ theo thứ nào. Tất cả các ngày trong tuần hiện đang áp dụng theo giờ mở
          cửa chung ở trên.
        </p>
      ) : (
        <div className="space-y-3">
          {weekdayExceptions.map((exc, excIdx) => {
            const usedByOthers = new Set(
              weekdayExceptions.filter((_, idx) => idx !== excIdx).map((e) => e.dayOfWeek),
            )
            const availableDays = weekdays
              .map((name, n) => ({ name, value: n + 1 }))
              .filter((w) => !usedByOthers.has(w.value))
            return (
              <div
                key={exc.dayOfWeek}
                className="rounded-2xl border border-emerald-200/80 bg-white p-4 shadow-xs space-y-3 dark:border-emerald-500/20 dark:bg-slate-900"
              >
                <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 pb-2.5">
                  <div className="flex items-center gap-3">
                    <span className="text-xs font-bold uppercase text-emerald-800">
                      Thứ áp dụng:
                    </span>
                    <select
                      className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-800 focus:border-emerald-500 focus:outline-hidden"
                      value={exc.dayOfWeek}
                      onChange={(e) => updateExceptionDay(excIdx, Number(e.target.value))}
                      disabled={busy}
                    >
                      {availableDays.map((d) => (
                        <option key={d.value} value={d.value}>
                          {d.name}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="flex items-center gap-4">
                    <div className="flex items-center gap-3 text-xs">
                      <label className="flex items-center gap-1.5 cursor-pointer font-medium text-slate-700">
                        <input
                          type="radio"
                          name={`exc-status-${excIdx}`}
                          checked={!exc.closed}
                          onChange={() => updateExceptionClosed(excIdx, false)}
                          disabled={busy}
                        />
                        Mở theo giờ riêng
                      </label>
                      <label className="flex items-center gap-1.5 cursor-pointer font-medium text-slate-700">
                        <input
                          type="radio"
                          name={`exc-status-${excIdx}`}
                          checked={exc.closed}
                          onChange={() => updateExceptionClosed(excIdx, true)}
                          disabled={busy}
                        />
                        Đóng cửa cả ngày
                      </label>
                    </div>
                    <button
                      type="button"
                      className="text-xs font-medium text-red-600 hover:text-red-700 underline"
                      disabled={busy}
                      onClick={() => removeWeekdayException(excIdx)}
                    >
                      Xóa ngoại lệ
                    </button>
                  </div>
                </div>

                {exc.closed ? (
                  <p className="text-xs text-amber-800 bg-amber-50 rounded-lg p-2.5">
                    ⚠️ Sân sẽ đóng cửa cả ngày vào <strong>{weekdays[exc.dayOfWeek - 1]}</strong>{' '}
                    hàng tuần (không nhận đặt sân).
                  </p>
                ) : (
                  <div className="space-y-3">
                    {exc.ranges.map((r, rIdx) => (
                      <div
                        key={rIdx}
                        className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3"
                      >
                        <div className="mb-2 flex items-center justify-between text-xs">
                          <span className="font-semibold text-slate-700">
                            {rIdx === 0
                              ? `Khoảng 1 (Ca chính - ${weekdays[exc.dayOfWeek - 1]})`
                              : `Khoảng 2 (Ca phụ - ${weekdays[exc.dayOfWeek - 1]})`}
                          </span>
                          {rIdx > 0 && (
                            <button
                              type="button"
                              className="text-xs text-red-600 hover:text-red-700 underline"
                              disabled={busy}
                              onClick={() => removeExceptionRange(excIdx, rIdx)}
                            >
                              Bỏ khoảng 2
                            </button>
                          )}
                        </div>
                        <div className="grid gap-3 sm:grid-cols-2">
                          <label className="grid gap-1 text-xs font-medium text-slate-700">
                            Mở cửa
                            <Input
                              type="time"
                              value={r.opensAt}
                              onChange={(e) =>
                                updateExceptionRange(excIdx, rIdx, 'opensAt', e.target.value)
                              }
                              required
                              disabled={busy}
                            />
                          </label>
                          <label className="grid gap-1 text-xs font-medium text-slate-700">
                            Đóng cửa
                            <Input
                              type="time"
                              value={r.closesAt}
                              onChange={(e) =>
                                updateExceptionRange(excIdx, rIdx, 'closesAt', e.target.value)
                              }
                              required
                              disabled={busy}
                            />
                          </label>
                        </div>
                      </div>
                    ))}
                    {exc.ranges.length < 2 && (
                      <button
                        type="button"
                        className="text-xs font-medium text-emerald-700 hover:underline"
                        disabled={busy}
                        onClick={() => addExceptionRange(excIdx)}
                      >
                        + Thêm ca phụ cho {weekdays[exc.dayOfWeek - 1]}
                      </button>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
