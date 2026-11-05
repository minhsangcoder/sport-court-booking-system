'use client'
import { Input } from '@/components/ui/input'
import type { Facility } from '@/features/facility/types'
import { displayLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import { useEffect, useMemo, useState } from 'react'

export type PickedFacility = Pick<Facility, 'id' | 'name' | 'status'>

export const fold = (value: string) =>
  value
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLocaleLowerCase('vi')
    .replace(/đ/g, 'd')

/** Short, readable facility code: the first 8 characters of its id, like booking codes. */
export const facilityCode = (id: string) => id.slice(0, 8).toUpperCase()

/** Facility chooser: the alphabetical list is always shown; the search box narrows it by name or code. */
export function FacilityPicker({ onPick }: { onPick: (facility: PickedFacility) => void }) {
  const [text, setText] = useState('')
  const [debounced, setDebounced] = useState('')
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(text.trim()), 300)
    return () => clearTimeout(timer)
  }, [text])
  const all = useApi<Facility[]>('/admin/facilities')
  // The server search also covers facilities beyond the first page and partial codes.
  const searched = useApi<Facility[]>(
    debounced ? `/admin/facilities?q=${encodeURIComponent(debounced)}` : null,
  )
  const options = useMemo(() => {
    const needle = fold(text.trim())
    const byId = new Map<string, Facility>()
    for (const f of [...(all.data ?? []), ...(debounced ? (searched.data ?? []) : [])])
      if (!needle || fold(f.name).includes(needle) || f.id.toLowerCase().includes(needle))
        byId.set(f.id, f)
    return [...byId.values()].sort((a, b) => a.name.localeCompare(b.name, 'vi'))
  }, [all.data, searched.data, debounced, text])
  const loading = all.loading || (debounced !== '' && searched.loading)
  const pick = (f: Facility) => onPick({ id: f.id, name: f.name, status: f.status })
  return (
    <div>
      <label className="grid gap-2 text-sm">
        Tìm cơ sở
        <Input
          type="search"
          value={text}
          placeholder="Nhập tên hoặc mã cơ sở"
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && options[0]) {
              e.preventDefault()
              pick(options[0])
            }
          }}
        />
      </label>
      <p className="mt-4 text-xs text-slate-500" aria-live="polite">
        {loading && options.length === 0
          ? 'Đang tải danh sách cơ sở…'
          : `${options.length} cơ sở${text.trim() ? ' phù hợp' : ''}`}
      </p>
      {all.error && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          {all.error}
          <button type="button" className="ml-3 underline" onClick={all.reload}>
            Thử lại
          </button>
        </p>
      )}
      {!loading && !all.error && options.length === 0 && (
        <p className="mt-2 text-sm text-slate-500">Không tìm thấy cơ sở phù hợp.</p>
      )}
      <ul className="mt-2 grid max-h-[28rem] gap-2 overflow-y-auto pr-1 md:grid-cols-2">
        {options.map((f) => (
          <li key={f.id}>
            <button
              type="button"
              onClick={() => pick(f)}
              className="w-full rounded-2xl border border-slate-200 px-4 py-3 text-left transition hover:border-emerald-400 hover:bg-emerald-50 focus-visible:border-emerald-500 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-emerald-500/20 dark:border-slate-700 dark:hover:bg-emerald-950"
            >
              <p className="font-semibold">{f.name}</p>
              <p className="mt-0.5 break-all text-xs text-slate-500">
                Mã {facilityCode(f.id)} · {displayLabel(f.status)}
              </p>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
