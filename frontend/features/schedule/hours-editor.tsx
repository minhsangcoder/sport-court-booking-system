'use client'
import { Button } from '@/components/ui/button'
import { card, outlinePill, primaryPill, redNotice } from '@/features/facility/owner-ui'
import { ImpactNotice } from '@/features/integration/configuration-impact'
import { CommonHoursForm } from '@/features/schedule/common-hours-form'
import { Interval } from '@/features/schedule/hours-model'
import { useHoursEditor } from '@/features/schedule/use-hours-editor'
import { WeekdayExceptionsForm } from '@/features/schedule/weekday-exceptions-form'
export function HoursEditor({
  facilityId,
  courtId,
  initial,
  reload,
  onSaved,
}: {
  facilityId: string
  courtId: string
  initial: Interval[]
  reload: () => void
  onSaved: (message: string) => void
}) {
  const model = useHoursEditor({ facilityId, courtId, initial, reload, onSaved })
  const { save, busy, resetToFacility, impact, error } = model
  return (
    <form onSubmit={save} className={`${card} home-rise space-y-6 p-5 sm:p-6`}>
      <CommonHoursForm model={model} />

      {/* SECTION: Ngoại lệ theo thứ trong tuần */}
      <WeekdayExceptionsForm model={model} />

      <div className="flex flex-wrap items-center gap-3 border-t border-slate-100 pt-4 dark:border-slate-800">
        <Button type="submit" className={primaryPill} disabled={busy}>
          {busy ? 'Đang lưu…' : 'Lưu lịch'}
        </Button>
        {courtId && initial.length > 0 && (
          <Button
            type="button"
            variant="outline"
            className={outlinePill}
            disabled={busy}
            onClick={resetToFacility}
          >
            Kế thừa lịch cơ sở
          </Button>
        )}
      </div>

      <ImpactNotice impact={impact} />
      {error && (
        <p role="alert" className={redNotice}>
          {error}
        </p>
      )}
      <p className="text-xs text-slate-500">
        * Lưu ý: Các ngoại lệ theo thứ được ưu tiên trước giờ mở cửa chung. Thay đổi lịch không tự
        hủy lịch đặt đã xác nhận.
      </p>
    </form>
  )
}
