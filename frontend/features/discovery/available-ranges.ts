import type { Slot } from '@/features/schedule/types'

export function availableRanges(slots: Slot[]) {
  const ranges: { startsAt: string; endsAt: string }[] = []
  for (const slot of [...slots].sort((a, b) => a.startsAt.localeCompare(b.startsAt))) {
    if (slot.state !== 'AVAILABLE') continue
    const previous = ranges[ranges.length - 1]
    if (previous && Date.parse(previous.endsAt) === Date.parse(slot.startsAt))
      previous.endsAt = slot.endsAt
    else ranges.push({ startsAt: slot.startsAt, endsAt: slot.endsAt })
  }
  return ranges
}

export function rangeSelection(slots: Slot[], startsAt: string, endsAt: string) {
  const selected = slots
    .filter(
      (s) =>
        Date.parse(s.startsAt) >= Date.parse(startsAt) &&
        Date.parse(s.endsAt) <= Date.parse(endsAt),
    )
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
  if (
    !selected.length ||
    selected.some((s) => s.state !== 'AVAILABLE') ||
    Date.parse(selected[0].startsAt) !== Date.parse(startsAt) ||
    Date.parse(selected[selected.length - 1].endsAt) !== Date.parse(endsAt) ||
    selected.some((s, i) => i > 0 && Date.parse(selected[i - 1].endsAt) !== Date.parse(s.startsAt))
  )
    return []
  return selected
}
