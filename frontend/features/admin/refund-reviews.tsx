'use client'
import { Button } from '@/components/ui/button'
import { AdminNav } from '@/features/admin/admin-nav'
import { Card, Frame, Status, useMutation } from '@/features/integration/shared'
import { businessApi, type RefundReview } from '@/lib/business-api'
import { displayLabel } from '@/lib/display-labels'
import { translateError } from '@/lib/error-translator'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
export function RefundReviews({ id }: { id?: string }) {
  const r = useApi<RefundReview[]>(id ? null : '/admin/payments/refunds'),
    one = useApi<RefundReview>(id ? `/admin/payments/refunds/${id}` : null),
    a = useMutation(),
    [key, setKey] = useState(() => crypto.randomUUID())
  return (
    <Frame title="Rà soát yêu cầu hoàn tiền">
      <AdminNav active="refunds" />
      <p className="rounded-2xl bg-amber-50 p-4 text-sm">
        Chưa có số tiền hoặc lệnh hoàn tiền. Ghi chú không thực thi giao dịch; người nhận phải là
        người thanh toán gốc.
      </p>
      <Status loading={r.loading || one.loading} error={r.error || one.error || a.error} />
      {r.data?.map((x) => (
        <Link
          key={x.id}
          className="block p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
          href={`/admin/refunds/${x.id}`}
        >
          {translateError(x.reason, { preserveUnknown: true })} · {displayLabel(x.state)}
        </Link>
      ))}
      {one.data && (
        <>
          <Card title={displayLabel(one.data.state)}>
            <p>{translateError(one.data.reason, { preserveUnknown: true })}</p>
            <p className="break-all text-sm">Người yêu cầu: {one.data.requester_id}</p>
            <p className="break-all text-sm">Người thanh toán gốc: {one.data.beneficiary_id}</p>
          </Card>
          <form
            className="space-y-4"
            onChange={() => setKey(crypto.randomUUID())}
            onSubmit={(e) => {
              e.preventDefault()
              const f = new FormData(e.currentTarget)
              void a.run(
                () => businessApi.refundNote(id!, String(f.get('note')), key),
                () => {
                  one.reload()
                  setKey(crypto.randomUUID())
                },
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
              Lưu ghi chú, không thực thi hoàn tiền
            </Button>
          </form>
          {one.data.reviews?.map((n) => (
            <Card key={n.id} title={new Date(n.created_at).toLocaleString('vi-VN')}>
              <p>{n.note}</p>
            </Card>
          ))}
        </>
      )}
    </Frame>
  )
}
