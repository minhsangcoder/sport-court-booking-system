'use client'
import Link from 'next/link'
import { useState } from 'react'
import { useApi } from '@/lib/use-api'
import { RequireAuth } from '@/features/auth/require-auth'
import { PageBanner, bannerGhost } from '@/components/page-banner'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import type { Category } from '@/features/facility/types'
import type { Listing } from './types'
export function TransferMarket() {
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded />
    </RequireAuth>
  )
}

function Loaded() {
  const [query, setQuery] = useState('')
  const [path, setPath] = useState('/transfers')
  const categories = useApi<Category[]>('/sport-categories')
  const r = useApi<Listing[]>(path)

  function search(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    const p = new URLSearchParams()
    for (const k of ['q', 'category', 'maxPrice', 'sort']) {
      const value = String(form.get(k) || '')
      if (value) p.set(k, value)
    }
    if (form.get('date')) {
      const date = String(form.get('date'))
      p.set('after', `${date}T00:00:00+07:00`)
      p.set('before', `${date}T23:59:59+07:00`)
    }
    setPath(`/transfers?${p}`)
  }

  return (
    <>
      <PageBanner
        eyebrow="Chợ chuyển nhượng SportHub"
        title="Nhận lại lượt đặt sân"
        description="Chọn lịch phù hợp từ các lượt đặt đã thanh toán, người bán nhận 100% giá chốt."
        icon="shuttle"
        actions={
          <Link className={bannerGhost} href="/customer/transfers">
            Tin và giao dịch của tôi
          </Link>
        }
      />
      <div className="mx-auto max-w-6xl px-4 sm:px-6 py-6 sm:py-8">
        <form
          onSubmit={search}
          className="mt-6 grid gap-3.5 rounded-xl border border-slate-200 bg-white p-4 shadow-2xs sm:grid-cols-3"
        >
          <label className="grid gap-1.5 text-xs font-medium text-slate-700">
            Khu vực hoặc sân
            <Input
              name="q"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Tên sân, tỉnh hoặc xã / phường"
            />
          </label>
          <label className="grid gap-1.5 text-xs font-medium text-slate-700">
            Môn thể thao
            <select
              name="category"
              className="h-9 rounded-lg border border-slate-300 bg-white px-3 text-[13px] text-slate-900"
            >
              <option value="">Tất cả</option>
              {categories.data?.map((c) => (
                <option value={c.id} key={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label className="grid gap-1.5 text-xs font-medium text-slate-700">
            Ngày chơi
            <Input name="date" type="date" />
          </label>
          <label className="grid gap-1.5 text-xs font-medium text-slate-700">
            Giá tối đa (VND)
            <Input name="maxPrice" type="number" min={0} step={1000} />
          </label>
          <label className="grid gap-1.5 text-xs font-medium text-slate-700">
            Sắp xếp
            <select
              name="sort"
              className="h-9 rounded-lg border border-slate-300 bg-white px-3 text-[13px] text-slate-900"
            >
              <option value="start">Giờ chơi gần nhất</option>
              <option value="price">Giá thấp nhất</option>
              <option value="discount">Mức giảm cao nhất</option>
            </select>
          </label>
          <Button type="submit" className="mt-auto bg-emerald-700 text-white hover:bg-emerald-800">
            Tìm lượt đặt
          </Button>
        </form>

        {r.loading && (
          <p role="status" className="mt-6 text-xs text-slate-500">
            Đang tải chợ chuyển nhượng…
          </p>
        )}
        {r.error && (
          <p role="alert" className="mt-6 text-xs text-red-700">
            {r.error}
            <button className="ml-3 font-semibold underline" onClick={r.reload}>
              Thử lại
            </button>
          </p>
        )}
        {r.data?.length === 0 && (
          <p className="mt-6 rounded-xl border border-slate-200 bg-white p-6 text-xs text-slate-500">
            Chưa có lượt đặt phù hợp. Thử đổi bộ lọc hoặc tìm sân để đặt trực tiếp.
          </p>
        )}
        <div className="mt-6 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {r.data?.map((l) => (
            <ListingCard key={l.id} listing={l} />
          ))}
        </div>
      </div>
    </>
  )
}

export function ListingCard({ listing: l }: { listing: Listing }) {
  return (
    <Link
      href={`/transfers/${l.id}`}
      className="rounded-xl border border-slate-200 bg-white p-4 shadow-2xs transition hover:border-slate-300 hover:shadow-sm"
    >
      <div className="flex items-center justify-between gap-2">
        <span className="rounded-md border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-800">
          {l.own ? 'TIN CỦA BẠN' : `GIẢM ${Math.round((1 - l.price / l.originalAmount) * 100)}%`}
        </span>
        <span className="text-[11px] text-slate-500">
          {l.venue.ward} · {l.venue.province}
        </span>
      </div>
      <h2 className="mt-3 text-[15px] font-semibold text-slate-900">{l.venue.facilityName}</h2>
      <p className="mt-1 text-xs text-slate-500">{l.venue.courtName}</p>
      <p className="mt-3 text-xs text-slate-700">
        {new Date(l.startsAt).toLocaleString('vi-VN', { timeZone: l.venue.timezone })} →{' '}
        {new Date(l.endsAt).toLocaleTimeString('vi-VN', {
          timeZone: l.venue.timezone,
          hour: '2-digit',
          minute: '2-digit',
        })}
      </p>
      <div className="mt-4 flex items-baseline gap-2 border-t border-slate-100 pt-3">
        <strong className="text-base font-bold text-slate-900">{money(l.price, l.currency)}</strong>
        <span className="text-xs text-slate-400 line-through">
          {money(l.originalAmount, l.currency)}
        </span>
      </div>
    </Link>
  )
}
