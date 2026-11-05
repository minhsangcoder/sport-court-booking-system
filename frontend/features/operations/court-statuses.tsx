'use client'
import { confirmDialog } from '@/components/confirm-dialog'
import { Button } from '@/components/ui/button'
import type { Court } from '@/features/facility/types'
import {
  ImpactNotice,
  readImpact,
  type ConfigurationImpact,
} from '@/features/integration/configuration-impact'
import { Card, Status, useMutation } from '@/features/integration/shared'
import { businessApi } from '@/lib/business-api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'

export function CourtStatuses({ id }: { id: string }) {
  const r = useApi<Court[]>(`/owner/facilities/${id}/courts`),
    a = useMutation(),
    [impact, setImpact] = useState<ConfigurationImpact | null>(null)
  return (
    <section className="space-y-4">
      <h2 className="text-2xl font-bold">Trạng thái sân</h2>
      <Status loading={r.loading} error={r.error || a.error} />
      <ImpactNotice impact={impact} />
      {r.data?.map((c) => (
        <Card key={c.id} title={c.name}>
          <p>{c.enabled ? 'Đang nhận đặt' : 'Tạm ngừng'}</p>
          <Button
            type="button"
            variant="outline"
            disabled={a.busy}
            onClick={async () => {
              if (
                !(await confirmDialog(
                  c.enabled
                    ? 'Tạm ngừng nhận lượt đặt mới trên sân này?'
                    : 'Kích hoạt lại sân này?',
                ))
              )
                return
              setImpact(null)
              void a.run(
                () =>
                  businessApi.courtStatus(c.id, !c.enabled).catch((error) => {
                    setImpact(readImpact(error))
                    throw error
                  }),
                r.reload,
              )
            }}
          >
            {c.enabled ? 'Tạm ngừng sân' : 'Kích hoạt sân'}
          </Button>
        </Card>
      ))}
    </section>
  )
}
