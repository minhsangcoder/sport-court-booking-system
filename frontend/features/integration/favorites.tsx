'use client'
import Link from 'next/link'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import { businessApi, type Favorite } from '@/lib/business-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { Button } from '@/components/ui/button'
import { Card, Frame, Status, useMutation } from './shared'
export function FavoriteButton({ id }: { id: string }) {
  const { user } = useAuth(),
    r = useApi<Favorite>(user ? `/facilities/${id}/favorite` : null),
    a = useMutation()
  if (!user)
    return (
      <Link
        className="text-sm text-emerald-700 underline"
        href={`/login?next=${encodeURIComponent('/facilities/' + id)}`}
      >
        Đăng nhập để lưu cơ sở
      </Link>
    )
  return (
    <div>
      <Button
        variant="outline"
        disabled={a.busy || r.loading}
        onClick={() => a.run(() => businessApi.favorite(id, !r.data?.favorite), r.reload)}
      >
        {r.data?.favorite ? 'Bỏ yêu thích' : 'Lưu cơ sở yêu thích'}
      </Button>
      <Status error={a.error || r.error} />
    </div>
  )
}
export function FavoritesPage() {
  return (
    <RequireAuth>
      <Favorites />
    </RequireAuth>
  )
}
function Favorites() {
  const [page, setPage] = useState(0),
    r = useApi<{ items: Favorite[]; page: number; size: number; total: number }>(
      `/facilities/favorites?page=${page}&size=20`,
    ),
    a = useMutation()
  return (
    <Frame title="Cơ sở yêu thích">
      <Status loading={r.loading} error={r.error || a.error} empty={r.data?.total === 0} />
      <div className="grid gap-4 sm:grid-cols-2">
        {r.data?.items.map((f) => (
          <Card key={f.facilityId} title={f.facility?.name ?? 'Cơ sở tạm thời không khả dụng'}>
            {f.facility && (
              <p className="text-slate-500">
                {f.facility.addressLine}, {f.facility.ward}, {f.facility.province}
              </p>
            )}
            {f.available && (
              <Link
                className="block text-emerald-700 underline"
                href={`/facilities/${f.facilityId}`}
              >
                Xem lịch sân
              </Link>
            )}
            <Button
              variant="outline"
              disabled={a.busy}
              onClick={() => a.run(() => businessApi.favorite(f.facilityId, false), r.reload)}
            >
              Bỏ yêu thích
            </Button>
          </Card>
        ))}
      </div>
      <div className="flex gap-3">
        <Button disabled={!page || a.busy} variant="outline" onClick={() => setPage(page - 1)}>
          Trang trước
        </Button>
        <Button
          disabled={!r.data || (page + 1) * 20 >= r.data.total}
          variant="outline"
          onClick={() => setPage(page + 1)}
        >
          Trang sau
        </Button>
      </div>
    </Frame>
  )
}
import { useState } from 'react'
