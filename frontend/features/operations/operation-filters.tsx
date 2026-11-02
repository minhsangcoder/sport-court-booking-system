'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { statusLabels } from '@/features/booking/types'
import type { useOperationBookings } from '@/features/operations/use-operation-bookings'
export function OperationFilters({
  model,
}: {
  model: Pick<
    ReturnType<typeof useOperationBookings>,
    | 'facilityId'
    | 'setSelection'
    | 'setPage'
    | 'busy'
    | 'facilities'
    | 'filter'
    | 'setFilter'
    | 'query'
    | 'setQuery'
    | 'bookings'
  >
}) {
  const {
    facilityId,
    setSelection,
    setPage,
    busy,
    facilities,
    filter,
    setFilter,
    query,
    setQuery,
    bookings,
  } = model
  return (
    <div className="my-6 flex flex-wrap gap-4">
      <label className="grid gap-2 text-sm">
        Cơ sở
        <select
          className="rounded-2xl border border-slate-200/90 bg-white p-3"
          value={facilityId || ''}
          onChange={(e) => {
            setSelection(e.target.value)
            setPage(0)
          }}
          disabled={busy}
        >
          {facilities?.map((f) => (
            <option value={f.id} key={f.id}>
              {f.name}
            </option>
          ))}
        </select>
      </label>
      <label className="grid gap-2 text-sm">
        Trạng thái
        <select
          className="rounded-2xl border border-slate-200/90 bg-white p-3"
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value)
            setPage(0)
          }}
        >
          <option value="">Tất cả</option>
          {Object.entries(statusLabels).map(([key, label]) => (
            <option key={key} value={key}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label className="grid gap-2 text-sm">
        Mã lượt đặt / tên / liên hệ
        <Input
          value={query}
          maxLength={180}
          onChange={(e) => {
            setQuery(e.target.value)
            setPage(0)
          }}
        />
      </label>
      <Button className="self-end" variant="outline" onClick={bookings.reload} disabled={busy}>
        Làm mới
      </Button>
    </div>
  )
}
