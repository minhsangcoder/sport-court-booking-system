'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { type Preview, weekdays } from '@/features/schedule/types'
import type { usePricing } from '@/features/schedule/use-pricing'
import { api } from '@/lib/api'
import { useState } from 'react'
import { pricingInput } from './pricing-input'
export function PricingForm({
  model,
}: {
  model: Pick<
    ReturnType<typeof usePricing>,
    | 'editing'
    | 'save'
    | 'busy'
    | 'selectClass'
    | 'courts'
    | 'categories'
    | 'types'
    | 'dateMode'
    | 'setDateMode'
    | 'setError'
    | 'setBusy'
    | 'facility'
    | 'setDraft'
    | 'setEditing'
  >
}) {
  const {
    editing,
    save,
    busy,
    selectClass,
    courts,
    categories,
    types,
    dateMode,
    setDateMode,
    setError,
    setBusy,
    facility,
    setDraft,
    setEditing,
  } = model
  const initialUnit = editing?.pricingUnit ?? (editing ? 'LEGACY_SLOT' : 'HOURLY')
  const [unit, setUnit] = useState(initialUnit)
  return (
    <form
      key={editing?.id ?? 'new'}
      onSubmit={save}
      className="space-y-6 rounded-2xl border border-slate-200/80 bg-white/90 p-6 shadow-xs backdrop-blur-sm sm:p-7 dark:border-slate-800 dark:bg-slate-900/90"
    >
      <div className="border-b border-slate-100 pb-4 dark:border-slate-800">
        <h3 className="text-base font-bold text-slate-900 dark:text-white">
          {editing ? 'Chỉnh sửa quy tắc giá' : 'Thêm quy tắc giá mới'}
        </h3>
        <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
          Khai báo chi tiết khung giờ và mức giá áp dụng cho sân theo giờ hoặc khung lịch.
        </p>
      </div>

      {/* Group 1: General Info & Scope */}
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Tên quy tắc
          <Input
            name="label"
            defaultValue={editing?.label ?? undefined}
            required
            maxLength={200}
            disabled={busy}
            className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
          />
        </label>
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Sân
          <select
            className={selectClass}
            name="courtId"
            defaultValue={editing?.courtId ?? undefined}
            disabled={busy}
          >
            <option value="">Toàn cơ sở</option>
            {courts.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Môn thể thao
          <select
            className={selectClass}
            name="sportCategoryId"
            defaultValue={editing?.sportCategoryId ?? undefined}
            disabled={busy}
          >
            <option value="">Tất cả môn</option>
            {categories.data
              ?.filter((c) => c.active)
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
          </select>
        </label>
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Loại sân
          <select
            className={selectClass}
            name="courtTypeId"
            defaultValue={editing?.courtTypeId ?? ''}
          >
            <option value="">Tất cả loại sân</option>
            {types.data
              ?.filter((t) => t.active || t.id === editing?.courtTypeId)
              .map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
          </select>
        </label>
      </div>

      {/* Group 2: Schedule & Period */}
      <div className="grid gap-4 sm:grid-cols-2 border-t border-slate-100 pt-5 dark:border-slate-800">
        <div className="sm:col-span-2">
          <label className="inline-flex items-center gap-3 rounded-xl border border-slate-200 bg-slate-50/60 px-4 py-2.5 text-xs font-bold uppercase tracking-wider text-slate-700 cursor-pointer hover:bg-slate-100/70 transition-all dark:border-slate-700 dark:bg-slate-800/80 dark:text-slate-300">
            <input
              type="checkbox"
              checked={dateMode}
              onChange={(e) => setDateMode(e.target.checked)}
              disabled={busy}
              className="size-4 rounded border-slate-300 text-emerald-600 focus:ring-emerald-500 accent-emerald-600 cursor-pointer"
            />
            Áp dụng một ngày cụ thể
          </label>
        </div>

        {dateMode ? (
          <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400 sm:col-span-2">
            Ngày cụ thể
            <Input
              name="specificDate"
              defaultValue={editing?.specificDate ?? undefined}
              type="date"
              required
              disabled={busy}
              className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
            />
          </label>
        ) : (
          <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400 sm:col-span-2">
            Ngày trong tuần
            <select
              className={selectClass}
              name="dayOfWeek"
              defaultValue={editing?.dayOfWeek ?? undefined}
              disabled={busy}
            >
              {weekdays.map((day, i) => (
                <option key={day} value={i + 1}>
                  {day}
                </option>
              ))}
            </select>
          </label>
        )}

        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Từ giờ
          <Input
            name="startsAt"
            defaultValue={editing?.startsAt ?? undefined}
            type="time"
            required
            disabled={busy}
            className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
          />
        </label>
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Đến giờ
          <Input
            name="endsAt"
            defaultValue={editing?.endsAt ?? undefined}
            type="time"
            required
            disabled={busy}
            className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
          />
        </label>
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Hiệu lực từ
          <Input
            name="effectiveFrom"
            defaultValue={editing?.effectiveFrom ?? undefined}
            type="date"
            required
            disabled={busy}
            className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
          />
        </label>
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Hiệu lực đến (tùy chọn)
          <Input
            name="effectiveTo"
            defaultValue={editing?.effectiveTo ?? undefined}
            type="date"
            disabled={busy}
            className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
          />
        </label>
      </div>

      {/* Group 3: Price Unit & Amount */}
      <div className="grid gap-4 sm:grid-cols-2 border-t border-slate-100 pt-5 dark:border-slate-800">
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Đơn vị tính giá
          <select
            name="pricingUnit"
            className={selectClass}
            value={unit}
            disabled={busy}
            onChange={(event) => setUnit(event.target.value as 'HOURLY' | 'LEGACY_SLOT')}
          >
            <option value="HOURLY">Theo giờ, tính theo số phút sử dụng</option>
            <option value="LEGACY_SLOT">Theo khung lịch cũ</option>
          </select>
        </label>
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          {unit === 'HOURLY' ? 'Giá theo giờ (đồng/giờ)' : 'Giá theo khung lịch cũ (đồng/khung)'}
          <Input
            key={unit}
            name="price"
            defaultValue={
              unit === initialUnit
                ? ((unit === 'HOURLY' ? editing?.pricePerHour : editing?.pricePerSlot) ?? undefined)
                : undefined
            }
            type="number"
            min={unit === 'HOURLY' ? 1 : 0.01}
            max={unit === 'HOURLY' ? 9999999999 : 9999999999.99}
            step={unit === 'HOURLY' ? 1 : 0.01}
            required
            disabled={busy}
            className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
          />
        </label>
        <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
          Ưu tiên trong cùng cấp
          <Input
            name="priority"
            type="number"
            min={0}
            max={10000}
            defaultValue={editing?.priority ?? 0}
            required
            disabled={busy}
            className="h-11 rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
          />
        </label>
        <div className="flex items-end gap-2.5">
          <Button
            className="h-11 w-full rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 px-6 font-bold text-white shadow-md shadow-emerald-600/20 transition-all hover:from-emerald-500 hover:to-teal-500 hover:scale-[1.01] active:scale-[0.99] disabled:opacity-50"
            disabled={busy}
            type="submit"
          >
            {busy ? 'Đang lưu…' : editing ? 'Lưu phiên bản giá mới' : 'Thêm quy tắc giá'}
          </Button>
          {editing && (
            <Button
              type="button"
              variant="outline"
              className="h-11 rounded-xl border-slate-300 bg-white font-semibold text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300"
              onClick={() => {
                setEditing(null)
                setDraft(null)
              }}
            >
              Hủy chỉnh sửa
            </Button>
          )}
        </div>
      </div>

      {/* Section: Preview */}
      <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50/70 p-5 dark:border-slate-800 dark:bg-slate-900/60">
        <h4 className="text-xs font-bold uppercase tracking-wider text-slate-700 dark:text-slate-300">
          Xem trước thử nghiệm giá (dự thảo)
        </h4>
        <div className="mt-3.5 grid gap-4 sm:grid-cols-2">
          <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
            Sân xem giá dự thảo
            <select className={selectClass} name="previewCourt">
              {courts.data
                ?.filter((c) => c.enabled)
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </select>
          </label>
          <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-400">
            Ngày xem dự thảo
            <Input
              name="previewDate"
              type="date"
              className="h-11 rounded-xl border border-slate-200 bg-white px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/20 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
            />
          </label>
        </div>
        <div className="mt-4">
          <Button
            variant="outline"
            type="button"
            disabled={busy}
            className="h-11 rounded-xl border-slate-300 bg-white font-bold text-slate-700 shadow-2xs hover:bg-slate-100 hover:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
            onClick={(e) => {
              const form = e.currentTarget.closest('form')!
              if (!form.reportValidity()) return
              const f = new FormData(form),
                value = (name: string) => String(f.get(name) || '')
              if (!value('previewDate')) {
                setError('Chọn ngày xem dự thảo.')
                return
              }
              let rule
              try {
                rule = pricingInput(f, dateMode)
              } catch (failure) {
                setError(failure instanceof Error ? failure.message : 'Kiểm tra lại bảng giá.')
                return
              }
              setBusy(true)
              setError('')
              void api<Preview>(`/schedules/facilities/${facility.id}/preview/pricing`, {
                method: 'POST',
                body: JSON.stringify({
                  courtId: value('previewCourt'),
                  date: value('previewDate'),
                  replacesRuleId: editing?.id,
                  rule,
                }),
              })
                .then(setDraft)
                .catch((e) =>
                  setError(e instanceof Error ? e.message : 'Không xem được giá dự thảo'),
                )
                .finally(() => setBusy(false))
            }}
          >
            Xem giá dự thảo (không lưu)
          </Button>
        </div>
      </div>
    </form>
  )
}
