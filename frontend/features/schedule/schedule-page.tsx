'use client'
import { useFacility } from '@/features/facility/facility-store'
import {
  OwnerPageHead,
  Panel,
  emeraldNotice,
  fieldLabel,
  outlinePill,
  redNotice,
} from '@/features/facility/owner-ui'
import { Eye } from 'lucide-react'
import type { Court } from '@/features/facility/types'
import { Exceptions } from '@/features/schedule/date-exceptions'
import { HoursEditor } from '@/features/schedule/hours-editor'
import { type Hours } from '@/features/schedule/types'
import { timezoneLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
export function SchedulePage() {
  const { facility, basePath } = useFacility()
  const hours = useApi<Hours[]>(`/schedules/facilities/${facility.id}/hours`)
  const courts = useApi<Court[]>(`/owner/facilities/${facility.id}/courts`)
  const [scope, setScope] = useState('')
  const [feedback, setFeedback] = useState('')
  return (
    <section className="space-y-6">
      <OwnerPageHead
        title="Lịch mở cửa"
        description={`Giờ theo ${timezoneLabel(facility.timezone)}. Sân dùng lịch cơ sở khi chưa có lịch riêng.`}
        actions={
          <Link className={outlinePill} href={`${basePath}/schedule/preview`}>
            <Eye className="size-4" />
            Xem trước lịch và giá
          </Link>
        }
      />
      <label className={`${fieldLabel} max-w-sm`}>
        Phạm vi lịch
        <select
          className="rounded-xl border bg-white px-3.5 dark:bg-slate-800"
          value={scope}
          onChange={(e) => {
            setScope(e.target.value)
            setFeedback('')
          }}
        >
          <option value="">Toàn cơ sở</option>
          {courts.data?.map((c) => (
            <option value={c.id} key={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </label>
      {(hours.error || courts.error) && (
        <p role="alert" className={redNotice}>
          {hours.error || courts.error}
          <button
            className="ml-3 underline"
            onClick={() => {
              hours.reload()
              courts.reload()
            }}
          >
            Thử lại
          </button>
        </p>
      )}
      {hours.loading ? (
        <p role="status">Đang tải lịch…</p>
      ) : (
        hours.data && (
          <HoursEditor
            onSaved={setFeedback}
            key={scope + JSON.stringify(hours.data)}
            facilityId={facility.id}
            courtId={scope}
            initial={hours.data.filter((h) => (h.courtId || '') === scope)}
            reload={hours.reload}
          />
        )
      )}
      {feedback && (
        <p role="status" className={emeraldNotice}>
          {feedback}
        </p>
      )}
      <Panel>
        <Exceptions key={scope} facilityId={facility.id} courtId={scope} />
      </Panel>
    </section>
  )
}
