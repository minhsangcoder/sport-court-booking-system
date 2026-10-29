'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { calendarLabel, reportPreset } from '@/features/facility/demo-report'
import type { useFacilityReport } from '@/features/facility/report/use-facility-report'
import { timezoneLabel } from '@/lib/display-labels'
export function ReportFilters({
  model,
}: {
  model: Pick<
    ReturnType<typeof useFacilityReport>,
    'apply' | 'draft' | 'setDraft' | 'facility' | 'range' | 'error' | 'moneySource' | 'setMoneySource'
  >
}) {
  const { apply, draft, setDraft, facility, range, error, moneySource, setMoneySource } = model
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        apply(draft)
      }}
      className="flex flex-wrap items-end gap-4 rounded-2xl border bg-white p-5"
    >
      <label className="grid gap-2 text-sm">
        Nguồn tiền
        <select
          value={moneySource}
          onChange={(event) => setMoneySource(event.target.value as 'ALL' | 'CASH' | 'BANK')}
          className="h-10 rounded-lg border px-3"
        >
          <option value="ALL">Tất cả</option>
          <option value="CASH">Tiền mặt</option>
          <option value="BANK">Chuyển khoản ngân hàng</option>
        </select>
      </label>
      <label className="grid gap-2 text-sm">
        Từ ngày
        <Input
          name="fromDate"
          type="date"
          required
          value={draft.fromDate}
          onChange={(event) => setDraft({ ...draft, fromDate: event.target.value })}
        />
      </label>
      <label className="grid gap-2 text-sm">
        Đến hết ngày
        <Input
          name="toDate"
          type="date"
          required
          value={draft.toDate}
          onChange={(event) => setDraft({ ...draft, toDate: event.target.value })}
        />
      </label>
      <Button type="submit" className="bg-emerald-600 text-white">
        Xem báo cáo
      </Button>
      <div className="flex flex-wrap gap-2">
        {(
          [
            ['7', '7 ngày'],
            ['30', '30 ngày'],
            ['month', 'Tháng này'],
          ] as const
        ).map(([preset, label]) => (
          <Button
            key={preset}
            type="button"
            variant="outline"
            onClick={() => apply(reportPreset(facility.timezone, preset))}
          >
            {label}
          </Button>
        ))}
      </div>
      <p className="w-full text-xs text-slate-500">
        Theo giờ cơ sở ({timezoneLabel(facility.timezone)}). Tối đa 366 ngày. Đang xem{' '}
        {calendarLabel(range.fromDate)} – {calendarLabel(range.toDate)}.
      </p>
      {error && (
        <p role="alert" className="w-full text-sm text-red-700">
          {error}
        </p>
      )}
    </form>
  )
}
