/** Report periods are whole Vietnam calendar days (UTC+07:00), inclusive of `to`. */
export type DayRange = { from: string; to: string }

export const PRESETS = [
  { key: 'today', label: 'Hôm nay' },
  { key: '7d', label: '7 ngày qua' },
  { key: '30d', label: '30 ngày qua' },
  { key: 'month', label: 'Tháng này' },
  { key: 'quarter', label: 'Quý này' },
] as const
export type PresetKey = (typeof PRESETS)[number]['key']

/** Today's date in Vietnam as YYYY-MM-DD. */
export function vietnamToday(now = new Date()) {
  return new Date(now.getTime() + 7 * 3600_000).toISOString().slice(0, 10)
}

const shift = (day: string, days: number) =>
  new Date(Date.parse(`${day}T00:00:00Z`) + days * 86400_000).toISOString().slice(0, 10)

export function presetRange(key: PresetKey, today = vietnamToday()): DayRange {
  const [year, month] = today.split('-').map(Number)
  switch (key) {
    case 'today':
      return { from: today, to: today }
    case '7d':
      return { from: shift(today, -6), to: today }
    case '30d':
      return { from: shift(today, -29), to: today }
    case 'month':
      return { from: `${today.slice(0, 7)}-01`, to: today }
    case 'quarter': {
      const first = month - ((month - 1) % 3)
      return { from: `${year}-${String(first).padStart(2, '0')}-01`, to: today }
    }
  }
}

/** API query for a day range: from the start of `from` to the end of `to`, Vietnam time. */
export function rangeQuery({ from, to }: Partial<DayRange>) {
  const p = new URLSearchParams()
  if (from) p.set('from', new Date(`${from}T00:00:00+07:00`).toISOString())
  if (to) p.set('to', new Date(`${shift(to, 1)}T00:00:00+07:00`).toISOString())
  return p.toString()
}

/** The period of equal length that ends where the loaded one starts. */
export function previousPath(current: { from: string; to: string } | null) {
  if (!current) return null
  const from = Date.parse(current.from)
  const length = Date.parse(current.to) - from
  const p = new URLSearchParams({
    from: new Date(from - length).toISOString(),
    to: new Date(from).toISOString(),
  })
  return `/admin/bookings/statistics?${p}`
}

/** A loaded API period (end exclusive) as Vietnam dates, end inclusive. */
export function periodLabel(range: { from: string; to: string }) {
  const day = (ms: number) =>
    new Date(ms + 7 * 3600_000).toISOString().slice(0, 10).split('-').reverse().join('/')
  return { from: day(Date.parse(range.from)), to: day(Date.parse(range.to) - 1) }
}
