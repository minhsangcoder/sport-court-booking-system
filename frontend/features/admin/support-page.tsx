'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { AdminNav } from '@/features/admin/admin-nav'
import { Card, Frame, Status, selectStyle, useMutation } from '@/features/integration/shared'
import { businessApi, type SupportCase, type SupportInput } from '@/lib/business-api'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
export function SupportPage({ id }: { id?: string }) {
  return id ? <SupportDetail id={id} /> : <SupportList />
}
function SupportList() {
  const r = useApi<SupportCase[]>('/admin/bookings/complaints'),
    a = useMutation(),
    [key, setKey] = useState(() => crypto.randomUUID()),
    router = useRouter()
  return (
    <Frame title="Hồ sơ hỗ trợ & tranh chấp">
      <AdminNav active="disputes" />
      <p className="text-slate-500">
        Ghi nhận vụ việc được tiếp nhận bởi quản trị viên. Quyết định hồ sơ không thực thi hoàn
        tiền, tạm giữ tiền hoặc khoản trả người chuyển nhượng.
      </p>
      <form
        className="grid gap-4 p-6 sm:grid-cols-2 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
        onChange={() => setKey(crypto.randomUUID())}
        onSubmit={(e) => {
          e.preventDefault()
          const f = new FormData(e.currentTarget),
            value = (n: string) => String(f.get(n) || '')
          const body: SupportInput = {
            bookingId: value('bookingId'),
            reportedBy: value('reportedBy') || undefined,
            category: value('category') as SupportInput['category'],
            subject: value('subject'),
            description: value('description'),
            evidence: value('evidence') || undefined,
            paymentId: value('paymentId') || undefined,
            listingId: value('listingId') || undefined,
          }
          void a.run(
            () => businessApi.support(body, key),
            (c) => router.push(`/admin/disputes/${c.id}`),
          )
        }}
      >
        <label className="grid gap-2 text-sm">
          Mã lượt đặt
          <Input name="bookingId" required pattern="[0-9a-fA-F-]{36}" />
        </label>
        <label className="grid gap-2 text-sm">
          Loại vụ việc
          <select name="category" className={selectStyle}>
            <option value="FACILITY_QUALITY">Chất lượng cơ sở</option>
            <option value="PAYMENT_REVIEW">Rà soát thanh toán</option>
            <option value="TRANSFER_REVIEW">Rà soát chuyển nhượng</option>
          </select>
        </label>
        {[
          ['reportedBy', 'Mã người phản ánh (tùy chọn)'],
          ['paymentId', 'Mã thanh toán gốc (bắt buộc với thanh toán)'],
          ['listingId', 'Mã tin chuyển nhượng gốc (bắt buộc với chuyển nhượng)'],
        ].map(([name, label]) => (
          <label key={name} className="grid gap-2 text-sm">
            {label}
            <Input name={name} pattern="[0-9a-fA-F-]{36}" />
          </label>
        ))}
        <label className="grid gap-2 text-sm">
          Tiêu đề
          <Input name="subject" required maxLength={200} />
        </label>
        <label className="grid gap-2 text-sm">
          Mô tả
          <textarea
            name="description"
            required
            minLength={20}
            maxLength={10000}
            className="rounded-2xl border border-slate-200/90 p-3"
          />
        </label>
        <label className="grid gap-2 text-sm">
          Bằng chứng dạng văn bản
          <textarea
            name="evidence"
            maxLength={5000}
            className="rounded-2xl border border-slate-200/90 p-3"
          />
        </label>
        <Button type="submit" disabled={a.busy}>
          Ghi nhận hồ sơ hỗ trợ
        </Button>
      </form>
      <Status loading={r.loading} error={r.error || a.error} empty={r.data?.length === 0} />
      {r.data?.map((c) => (
        <Link
          key={c.id}
          href={`/admin/disputes/${c.id}`}
          className="block p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
        >
          <strong>{c.subject}</strong>
          <p className="mt-2 text-sm text-slate-500">
            {displayLabel(c.category)} · {displayLabel(c.state)} · lượt đặt{' '}
            {c.booking_id.slice(0, 8)}
          </p>
        </Link>
      ))}
    </Frame>
  )
}
function SupportDetail({ id }: { id: string }) {
  const r = useApi<SupportCase>(`/admin/bookings/complaints/${id}`),
    a = useMutation()
  return (
    <Frame title={r.data?.subject ?? 'Hồ sơ hỗ trợ'}>
      <Link href="/admin/disputes" className="font-semibold text-emerald-700 hover:underline">
        Danh sách hồ sơ
      </Link>
      <Status loading={r.loading} error={r.error || a.error} />
      {r.data && (
        <>
          <Card title={displayLabel(r.data.state)}>
            <p className="whitespace-pre-wrap">{r.data.description}</p>
            <p className="whitespace-pre-wrap text-sm text-slate-500">{r.data.evidence}</p>
            <p className="rounded-2xl bg-amber-50 p-4 text-sm">
              Kết quả ghi nhận không là hoàn tiền, khoản trả người chuyển nhượng hay giải quyết tài
              chính đã hoàn tất. Chưa có cơ chế gửi thông báo các bên.
            </p>
          </Card>
          {r.data.state !== 'DECISION_RECORDED' && (
            <form
              className="grid gap-4 p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
              onSubmit={(e) => {
                e.preventDefault()
                const f = new FormData(e.currentTarget)
                void a.run(
                  () =>
                    businessApi.supportDecision(
                      id,
                      String(f.get('action')) as
                        | 'START_REVIEW'
                        | 'REQUEST_INFORMATION'
                        | 'RECORD_DECISION',
                      String(f.get('note')),
                      r.data!.version,
                    ),
                  r.reload,
                )
              }}
            >
              <label className="grid gap-2 text-sm">
                Thao tác
                <select name="action" className={selectStyle}>
                  {['OPEN', 'INFORMATION_REQUIRED'].includes(r.data.state) && (
                    <option value="START_REVIEW">Bắt đầu rà soát</option>
                  )}
                  {['OPEN', 'IN_REVIEW'].includes(r.data.state) && (
                    <option value="REQUEST_INFORMATION">Yêu cầu thông tin bổ sung</option>
                  )}
                  {r.data.state === 'IN_REVIEW' && (
                    <option value="RECORD_DECISION">Ghi nhận kết quả rà soát</option>
                  )}
                </select>
              </label>
              <label className="grid gap-2 text-sm">
                Nội dung xử lý
                <textarea
                  name="note"
                  required
                  minLength={20}
                  maxLength={2000}
                  className="rounded-2xl border border-slate-200/90 p-3"
                />
              </label>
              <Button type="submit" disabled={a.busy}>
                Ghi nhận thao tác
              </Button>
            </form>
          )}
          <Card title="Lịch sử xử lý">
            {r.data.history?.map((h) => (
              <div key={h.id} className="border-b py-3">
                <strong className="text-sm">{displayLabel(h.action)}</strong>
                <p className="mt-2 whitespace-pre-wrap">{h.note}</p>
                <time className="text-xs text-slate-500">
                  {new Date(h.occurred_at).toLocaleString('vi-VN')}
                </time>
              </div>
            ))}
          </Card>
        </>
      )}
    </Frame>
  )
}
