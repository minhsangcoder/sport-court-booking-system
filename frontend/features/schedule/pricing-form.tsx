'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { StepTitle, card, fieldLabel, outlinePill, primaryPill } from '@/features/facility/owner-ui'
import { type Preview, money, weekdays } from '@/features/schedule/types'
import type { usePricing } from '@/features/schedule/use-pricing'
import { api } from '@/lib/api'
import { displayLabel } from '@/lib/display-labels'
import { Eye, Save, X } from 'lucide-react'
import { useState } from 'react'
import { pricingInput } from './pricing-input'

const inputClass = 'h-11 rounded-xl'
const section =
  'space-y-4 border-t border-slate-100 pt-5 first:border-0 first:pt-0 dark:border-slate-800'
const modeClass = (on: boolean) =>
  `cursor-pointer rounded-full px-4 py-2 text-sm font-semibold transition-colors ${
    on
      ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
      : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:ring-emerald-400 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700'
  }`

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
    | 'draft'
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
    draft,
    setDraft,
    setEditing,
  } = model
  const initialUnit = editing?.pricingUnit ?? (editing ? 'LEGACY_SLOT' : 'HOURLY')
  const [unit, setUnit] = useState(initialUnit)

  function preview(event: React.MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.closest('form')!
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
      .catch((e) => setError(e instanceof Error ? e.message : 'Không xem được giá dự thảo'))
      .finally(() => setBusy(false))
  }

  return (
    <form
      id="pricing-form"
      key={editing?.id ?? 'new'}
      onSubmit={save}
      className="home-rise grid scroll-mt-6 items-start gap-6 xl:grid-cols-[minmax(0,1fr)_320px]"
    >
      <div className={`${card} space-y-5 p-5 sm:p-6`}>
        <div>
          <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
            {editing ? 'Chỉnh sửa quy tắc giá' : 'Thêm quy tắc giá mới'}
          </h3>
          <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
            Điền lần lượt ba bước, rồi xem thử hoặc lưu quy tắc.
          </p>
        </div>

        <fieldset className={section}>
          <StepTitle step={1} title="Áp dụng cho" />
          <div className="grid gap-4 sm:grid-cols-2">
            <label className={`${fieldLabel} sm:col-span-2`}>
              Tên quy tắc
              <Input
                name="label"
                defaultValue={editing?.label ?? undefined}
                required
                maxLength={200}
                disabled={busy}
                placeholder="Ví dụ: Giờ vàng buổi tối"
                className={inputClass}
              />
            </label>
            <label className={`${fieldLabel} sm:col-span-2`}>
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
            <label className={fieldLabel}>
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
            <label className={fieldLabel}>
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
        </fieldset>

        <fieldset className={section}>
          <StepTitle step={2} title="Thời gian" />
          <div className="flex flex-wrap gap-2" role="group" aria-label="Kiểu lịch áp dụng">
            <button
              type="button"
              aria-pressed={!dateMode}
              disabled={busy}
              className={modeClass(!dateMode)}
              onClick={() => setDateMode(false)}
            >
              Lặp hằng tuần
            </button>
            <button
              type="button"
              aria-pressed={dateMode}
              disabled={busy}
              className={modeClass(dateMode)}
              onClick={() => setDateMode(true)}
            >
              Một ngày cụ thể
            </button>
          </div>
          <div className="grid gap-4 sm:grid-cols-3">
            {dateMode ? (
              <label className={fieldLabel}>
                Ngày cụ thể
                <Input
                  name="specificDate"
                  defaultValue={editing?.specificDate ?? undefined}
                  type="date"
                  required
                  disabled={busy}
                  className={inputClass}
                />
              </label>
            ) : (
              <label className={fieldLabel}>
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
            <label className={fieldLabel}>
              Từ giờ
              <Input
                name="startsAt"
                defaultValue={editing?.startsAt ?? undefined}
                type="time"
                required
                disabled={busy}
                className={inputClass}
              />
            </label>
            <label className={fieldLabel}>
              Đến giờ
              <Input
                name="endsAt"
                defaultValue={editing?.endsAt ?? undefined}
                type="time"
                required
                disabled={busy}
                className={inputClass}
              />
            </label>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className={fieldLabel}>
              Hiệu lực từ
              <Input
                name="effectiveFrom"
                defaultValue={editing?.effectiveFrom ?? undefined}
                type="date"
                required
                disabled={busy}
                className={inputClass}
              />
            </label>
            <label className={fieldLabel}>
              Hiệu lực đến (tùy chọn)
              <Input
                name="effectiveTo"
                defaultValue={editing?.effectiveTo ?? undefined}
                type="date"
                disabled={busy}
                className={inputClass}
              />
            </label>
          </div>
        </fieldset>

        <fieldset className={section}>
          <StepTitle step={3} title="Mức giá" />
          <div className="grid gap-4 sm:grid-cols-2">
            <label className={fieldLabel}>
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
            <label className={fieldLabel}>
              {unit === 'HOURLY'
                ? 'Giá theo giờ (đồng/giờ)'
                : 'Giá theo khung lịch cũ (đồng/khung)'}
              <Input
                key={unit}
                name="price"
                defaultValue={
                  unit === initialUnit
                    ? ((unit === 'HOURLY' ? editing?.pricePerHour : editing?.pricePerSlot) ??
                      undefined)
                    : undefined
                }
                type="number"
                min={unit === 'HOURLY' ? 1 : 0.01}
                max={unit === 'HOURLY' ? 9999999999 : 9999999999.99}
                step={unit === 'HOURLY' ? 1 : 0.01}
                required
                disabled={busy}
                className={inputClass}
              />
            </label>
            <label className={fieldLabel}>
              Ưu tiên trong cùng cấp
              <Input
                name="priority"
                type="number"
                min={0}
                max={10000}
                defaultValue={editing?.priority ?? 0}
                required
                disabled={busy}
                className={inputClass}
              />
              <span className="text-xs font-normal text-slate-500 dark:text-slate-400">
                Số lớn hơn được áp dụng trước khi hai quy tắc trùng giờ.
              </span>
            </label>
          </div>
        </fieldset>
      </div>

      <aside className="space-y-4 xl:sticky xl:top-6">
        <div className={`${card} space-y-3 p-5`}>
          <h4 className="text-sm font-extrabold tracking-tight text-slate-950 dark:text-white">
            Lưu quy tắc
          </h4>
          <Button className={`${primaryPill} w-full`} disabled={busy} type="submit">
            <Save className="size-4" />
            {busy ? 'Đang lưu…' : editing ? 'Lưu phiên bản giá mới' : 'Thêm quy tắc giá'}
          </Button>
          {editing && (
            <Button
              type="button"
              variant="outline"
              className={`${outlinePill} w-full`}
              onClick={() => {
                setEditing(null)
                setDraft(null)
              }}
            >
              <X className="size-4" />
              Hủy chỉnh sửa
            </Button>
          )}
        </div>

        <div className={`${card} space-y-3 p-5`}>
          <div>
            <h4 className="text-sm font-extrabold tracking-tight text-slate-950 dark:text-white">
              Xem thử trước khi lưu
            </h4>
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
              Không lưu và không ảnh hưởng tới khách.
            </p>
          </div>
          <label className={fieldLabel}>
            Sân
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
          <label className={fieldLabel}>
            Ngày
            <Input name="previewDate" type="date" className={inputClass} />
          </label>
          <Button
            variant="outline"
            type="button"
            disabled={busy}
            className={`${outlinePill} w-full`}
            onClick={preview}
          >
            <Eye className="size-4" />
            Xem giá dự thảo (không lưu)
          </Button>
          {draft && (
            <div className="rounded-2xl border border-amber-200/80 bg-amber-50/70 p-3 dark:border-amber-500/20 dark:bg-amber-500/5">
              <p className="text-xs font-bold text-amber-900 dark:text-amber-200">
                Giá dự thảo, chưa lưu cấu hình
              </p>
              <ul className="mt-2 max-h-56 divide-y divide-amber-200/60 overflow-y-auto text-xs dark:divide-amber-500/20">
                {draft.slots.map((slot) => (
                  <li
                    key={slot.startsAt}
                    className="flex items-center justify-between gap-2 py-1.5 text-slate-700 dark:text-slate-300"
                  >
                    <span>
                      {new Date(slot.startsAt).toLocaleTimeString('vi-VN', {
                        timeZone: draft.timezone,
                        hour: '2-digit',
                        minute: '2-digit',
                      })}{' '}
                      · {displayLabel(slot.state)}
                    </span>
                    <span className="font-bold text-slate-900 dark:text-white">
                      {slot.amount == null ? 'Chưa có giá' : money(slot.amount)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </aside>
    </form>
  )
}
