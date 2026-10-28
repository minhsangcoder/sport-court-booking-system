'use client'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Court, Facility } from '@/features/facility/types'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { useState } from 'react'
export function CounterPage({ owner = false }: { owner?: boolean }) {
  const params = useSearchParams()
  const owned = useApi<Facility[]>(owner ? '/owner/facilities' : null)
  const [selection, setSelection] = useState(params.get('facilityId') || '')
  const facility = owner
    ? owned.data?.find((f) => f.id === selection)?.id || owned.data?.[0]?.id
    : params.get('facilityId')
  const workspace = owner ? 'owner' : 'staff'
  const courts = useApi<Court[]>(facility ? `/facilities/${facility}/courts` : null)
  return (
    <RequireAuth roles={[owner ? 'OWNER' : 'STAFF']}>
      <div className="mx-auto max-w-4xl px-5 py-10">
        <Link
          className="text-sm text-slate-500"
          href={`/${workspace}/bookings${facility ? `?facilityId=${facility}` : ''}`}
        >
          Vận hành / Đặt tại quầy
        </Link>
        <h1 className="mt-6 text-3xl font-black">Chọn sân cho khách</h1>
        <p className="mt-3 text-sm text-slate-600">
          Tạo lượt đặt hộ khách có tài khoản hoặc khách vãng lai. Người thao tác không trở thành chủ
          lượt đặt.
        </p>
        {owner && owned.data && owned.data.length > 0 && (
          <label className="my-4 grid gap-2 text-sm">
            Cơ sở
            <select
              className="rounded-lg border p-3"
              value={facility || ''}
              onChange={(e) => setSelection(e.target.value)}
            >
              {owned.data.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.name}
                </option>
              ))}
            </select>
          </label>
        )}
        {owned.loading && <p role="status">Đang tải cơ sở…</p>}
        {owned.error && (
          <p role="alert" className="text-red-700">
            {owned.error}
          </p>
        )}
        {!facility && <p className="mt-4">Chọn cơ sở tại màn hình vận hành trước.</p>}
        {courts.loading && <p role="status">Đang tải sân…</p>}
        {courts.error && (
          <p role="alert" className="text-red-700">
            {courts.error}
          </p>
        )}
        {courts.data?.length === 0 && (
          <p className="mt-4 text-slate-500">Chưa có sân đang nhận đặt.</p>
        )}
        <div className="mt-6 grid gap-4 sm:grid-cols-2">
          {courts.data?.map((c) => (
            <Link
              key={c.id}
              href={`/${workspace}/counter-booking/${c.id}?facilityId=${facility}`}
              className="rounded-xl border p-5 font-semibold hover:border-emerald-500"
            >
              {c.name}
            </Link>
          ))}
        </div>
      </div>
    </RequireAuth>
  )
}
