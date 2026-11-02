'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Status, useMutation } from '@/features/integration/shared'
import { card, fieldLabel, outlinePill, primaryPill } from '@/features/transfers/transfer-styles'
import { QrUploadButton } from '@/features/operations/qr-checkin'
import { businessApi, type Attendance } from '@/lib/business-api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'

export function StaffGroupAttendance({
  id,
  allowed,
  readable = allowed,
}: {
  id: string
  allowed: boolean
  readable?: boolean
}) {
  const r = useApi<Attendance[]>(readable ? `/groups/${id}/attendance` : null),
    a = useMutation()
  const [memberToken, setMemberToken] = useState('')
  if (!readable || !r.data?.length) return null
  return (
    <section className={`${card} home-rise space-y-4 p-5 sm:p-6`}>
      <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
        Điểm danh theo thành viên
      </h3>
      <Status error={r.error || a.error} />
      <ul className="divide-y divide-slate-100 dark:divide-slate-800">
        {r.data.map((m) => (
          <li
            key={m.member_id}
            className="flex items-center justify-between gap-3 py-2.5 text-sm text-slate-700 dark:text-slate-300"
          >
            <span className="font-semibold">{m.display_name}</span>
            <span
              className={`rounded-full border px-3 py-1 text-xs font-semibold ${
                m.checked_in_at
                  ? 'border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-200'
                  : 'border-slate-200 bg-slate-100 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300'
              }`}
            >
              {m.checked_in_at ? 'Đã có mặt' : 'Chưa điểm danh'}
            </span>
          </li>
        ))}
      </ul>
      {allowed && (
        <>
          <form
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault()
              const val = memberToken || String(new FormData(e.currentTarget).get('token') || '')
              void a.run(() => businessApi.scanMember(id, val), r.reload)
            }}
          >
            <div className="flex flex-wrap items-end gap-3">
              <div className="grid flex-1 gap-2 text-sm">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-slate-700 dark:text-slate-300">
                    Mã QR thành viên
                  </span>
                  <QrUploadButton
                    onTokenDetected={(token) => {
                      setMemberToken(token)
                      void a.run(() => businessApi.scanMember(id, token), r.reload)
                    }}
                    disabled={a.busy}
                  />
                </div>
                <Input
                  name="token"
                  value={memberToken}
                  onChange={(e) => setMemberToken(e.target.value)}
                  placeholder="Nhập mã thành viên hoặc tải ảnh QR ở trên"
                  required
                  maxLength={1500}
                  className="h-11 rounded-xl"
                />
              </div>
              <Button type="submit" className={`${primaryPill} self-end`} disabled={a.busy}>
                Quét mã thành viên
              </Button>
            </div>
          </form>
          <form
            className="grid items-end gap-3 border-t border-slate-100 pt-4 sm:grid-cols-2 dark:border-slate-800"
            onSubmit={(e) => {
              e.preventDefault()
              const f = new FormData(e.currentTarget)
              void a.run(
                () =>
                  businessApi.manualMember(id, String(f.get('memberId')), String(f.get('reason'))),
                r.reload,
              )
            }}
          >
            <label className={fieldLabel}>
              Thành viên thực tế có mặt
              <select
                name="memberId"
                className="h-11 rounded-xl border border-slate-200 bg-white px-3.5 dark:border-slate-700 dark:bg-slate-800"
              >
                {r.data
                  .filter((m) => !m.checked_in_at)
                  .map((m) => (
                    <option key={m.member_id} value={m.member_id}>
                      {m.display_name}
                    </option>
                  ))}
              </select>
            </label>
            <label className={fieldLabel}>
              Lý do xác nhận thủ công
              <Input
                name="reason"
                required
                minLength={20}
                maxLength={500}
                className="h-11 rounded-xl"
              />
            </label>
            <Button
              disabled={a.busy || r.data.every((m) => m.checked_in_at)}
              className={`${outlinePill} justify-self-start`}
            >
              Xác nhận người có mặt
            </Button>
          </form>
        </>
      )}
    </section>
  )
}
