'use client'
import { Button } from '@/components/ui/button'
import { Card, Frame, Status, useMutation } from '@/features/integration/shared'
import { api } from '@/lib/api'
import { displayLabel } from '@/lib/display-labels'
import { translateError } from '@/lib/error-translator'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
type Reconciliation = {
  id: string
  bookingId: string
  paymentId: string
  reason: string
  state: string
  reviews: { id: string; actorId: string; note: string; createdAt: string }[]
}
export function ReconciliationReview({ id }: { id: string }) {
  const r = useApi<Reconciliation>(`/admin/bookings/reconciliation/${id}`),
    a = useMutation(),
    [key, setKey] = useState(() => crypto.randomUUID())
  return (
    <Frame title="Rà soát sai lệch lượt đặt">
      <Link
        className="font-semibold text-emerald-700 hover:underline"
        href="/admin/bookings/reconciliation"
      >
        Danh sách đối soát
      </Link>
      <Status loading={r.loading} error={r.error || a.error} />
      {r.data && (
        <>
          <Card title={displayLabel(r.data.state)}>
            <p>{translateError(r.data.reason, { preserveUnknown: true })}</p>
            <p className="text-sm text-amber-800">
              Ghi chú không xác nhận lượt đặt hoặc thực thi hoàn tiền.
            </p>
            <Link
              className="font-semibold text-emerald-700 hover:underline"
              href={`/admin/bookings/${r.data.bookingId}`}
            >
              Lượt đặt gốc
            </Link>
          </Card>
          <form
            className="space-y-4"
            onChange={() => setKey(crypto.randomUUID())}
            onSubmit={(e) => {
              e.preventDefault()
              const note = String(new FormData(e.currentTarget).get('note'))
              void a.run(
                () =>
                  api(`/admin/bookings/reconciliation/${id}/reviews`, {
                    method: 'POST',
                    headers: { 'Idempotency-Key': key },
                    body: JSON.stringify({ note }),
                  }),
                r.reload,
              )
            }}
          >
            <label className="grid gap-2 text-sm">
              Ghi chú rà soát
              <textarea
                name="note"
                required
                minLength={20}
                maxLength={2000}
                className="rounded-2xl border border-slate-200/90 p-3"
              />
            </label>
            <Button type="submit" disabled={a.busy}>
              Lưu ghi chú
            </Button>
          </form>
          {r.data.reviews.map((n) => (
            <Card key={n.id} title={new Date(n.createdAt).toLocaleString('vi-VN')}>
              <p>{n.note}</p>
            </Card>
          ))}
        </>
      )}
    </Frame>
  )
}
