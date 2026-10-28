'use client'
import { provinces } from '@/features/facility/provinces.data'
import type { Category } from '@/features/facility/types'
import { useApi } from '@/lib/use-api'
import { CalendarDays, MapPin, Search, Trophy, Map as MapIcon } from 'lucide-react'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
export function HomeSearch({ today }: { today: string }) {
  const router = useRouter()
  const [keyword, setKeyword] = useState(''),
    [sportId, setSportId] = useState(''),
    [provinceCode, setProvinceCode] = useState(''),
    [date, setDate] = useState(today)
  const categories = useApi<Category[]>('/sport-categories')
  function handleSearchSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const params = new URLSearchParams()
    if (keyword.trim()) params.set('q', keyword.trim())
    if (sportId) params.set('sportCategoryId', sportId)
    if (provinceCode) params.set('provinceCode', provinceCode)
    if (date) params.set('date', date)
    router.push(`/search?${params.toString()}`)
  }
  const field =
    'w-full min-w-0 cursor-pointer bg-transparent text-[15px] font-medium text-slate-900 placeholder:text-slate-400 focus:outline-none dark:text-white'
  const cell =
    'group/cell flex flex-col justify-center gap-0.5 px-5 py-3 transition-colors hover:bg-slate-50 focus-within:bg-slate-50 dark:hover:bg-slate-800 dark:focus-within:bg-slate-800'
  const label =
    'flex items-center gap-1.5 text-xs font-medium text-slate-500 group-focus-within/cell:text-emerald-700'
  const icon = 'size-3.5'
  const quick = [
    ['Pickleball', 'Pickleball Hà Nội'],
    ['Tennis', 'Quần vợt Đà Nẵng'],
    ['Cầu lông', 'Sân cầu lông trong nhà'],
  ]
  return (
    <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white text-slate-900 shadow-[0_24px_60px_-24px_rgba(2,6,23,0.5)] dark:border-slate-700 dark:bg-slate-900">
      <form
        onSubmit={handleSearchSubmit}
        className="grid grid-cols-1 divide-y divide-slate-200 sm:grid-cols-2 sm:divide-y-0 lg:grid-cols-[1.4fr_1fr_1fr_1fr_auto] lg:divide-x dark:divide-slate-700"
      >
        <label className={cell}>
          <span className={label}>
            <MapPin className={icon} strokeWidth={2} />
            Địa điểm
          </span>
          <input
            type="text"
            placeholder="Tên sân, đường, quận…"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            maxLength={180}
            className={field}
          />
        </label>

        <label className={cell}>
          <span className={label}>
            <Trophy className={icon} strokeWidth={2} />
            Bộ môn
          </span>
          <select
            aria-label="Chọn bộ môn thể thao"
            value={sportId}
            onChange={(e) => setSportId(e.target.value)}
            className={field}
          >
            <option value="">Tất cả bộ môn</option>
            {categories.data
              ?.filter((c) => c.active)
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
          </select>
        </label>

        <label className={cell}>
          <span className={label}>
            <CalendarDays className={icon} strokeWidth={2} />
            Ngày chơi
          </span>
          <input
            type="date"
            min={today}
            value={date}
            onChange={(e) => setDate(e.target.value)}
            required
            className={field}
          />
        </label>

        <label className={cell}>
          <span className={label}>
            <MapIcon className={icon} strokeWidth={2} />
            Khu vực
          </span>
          <select
            aria-label="Chọn tỉnh thành"
            value={provinceCode}
            onChange={(e) => setProvinceCode(e.target.value)}
            className={field}
          >
            <option value="">Tất cả tỉnh thành</option>
            {provinces.map((p) => (
              <option key={p.code} value={p.code}>
                {p.name}
              </option>
            ))}
          </select>
        </label>

        <button
          type="submit"
          className="flex min-h-16 cursor-pointer items-center justify-center gap-2 bg-emerald-700 px-9 text-[15px] font-semibold text-white transition-colors hover:bg-emerald-800 sm:col-span-2 lg:col-span-1"
        >
          <Search size={18} strokeWidth={2} />
          <span>Tìm sân</span>
        </button>
      </form>

      <div className="flex flex-wrap items-center gap-x-5 gap-y-1.5 border-t border-slate-200 bg-slate-50/80 px-5 py-2.5 text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-800/50">
        <span>Gợi ý</span>
        {quick.map(([q, text]) => (
          <button
            key={q}
            type="button"
            onClick={() => {
              setKeyword(q)
              router.push(`/search?q=${encodeURIComponent(q)}&date=${today}`)
            }}
            className="cursor-pointer font-medium text-slate-700 underline decoration-slate-300 underline-offset-4 transition-colors hover:text-emerald-700 hover:decoration-emerald-600 dark:text-slate-200"
          >
            {text}
          </button>
        ))}
      </div>
    </div>
  )
}
