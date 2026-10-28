export type Hours = {
  id: string
  facilityId: string
  courtId: string | null
  dayOfWeek: number
  opensAt: string
  closesAt: string
  slotMinutes: number
  version: number
}
export type ScheduleException = {
  id: string
  courtId: string | null
  date: string
  type: 'CLOSED' | 'SPECIAL_HOURS'
  opensAt: string | null
  closesAt: string | null
  reason: string
}
export type PriceRule = {
  id: string
  courtId: string | null
  sportCategoryId: string | null
  courtTypeId?: string | null
  dayOfWeek: number | null
  specificDate: string | null
  startsAt: string
  endsAt: string
  pricePerSlot: number
  pricingUnit?: 'LEGACY_SLOT' | 'HOURLY'
  pricePerHour?: number | null
  priority: number
  label: string
  effectiveFrom: string
  effectiveTo: string | null
  currency: string
  active: boolean
  version: number
}
export type Slot = {
  startsAt: string
  endsAt: string
  state: string
  reason: string | null
  amount: number | null
  currency: string
  ruleId: string | null
  ruleVersion: number
  ruleLabel: string | null
  pricePerHour?: number | null
  durationMinutes?: number
}
export type Preview = {
  facilityId: string
  courtId: string
  date: string
  timezone: string
  slots: Slot[]
  pricingMode?: 'LEGACY_SLOT' | 'HOURLY_INTERVAL_V1' | 'PRORATED_SLOT_INTERVAL_V1'
  selectionStepMinutes?: number | null
  minimumMinutes?: number | null
  maximumMinutes?: number | null
  policyVersion?: number
}
export type Quote = {
  facilityId: string
  courtId: string
  startsAt: string
  endsAt: string
  amount: number
  currency: string
  timezone: string
  segments: Slot[]
  quoteVersion?: 1 | 2
  pricingMode?: 'LEGACY_SLOT' | 'HOURLY_INTERVAL_V1' | 'PRORATED_SLOT_INTERVAL_V1'
  pricingRevision?: string
  policyVersion?: number
  durationMinutes?: number
  quoteHash?: string
  warnings?: { code: string; startsAt: string; endsAt: string }[]
  suggestions?: { courtId: string; startsAt: string; endsAt: string }[]
  moneyClass?: 'REAL' | 'DEMO'
  groupMinimumLeadSeconds?: number
  refundPolicy?: import('@/features/facility/refund-policy').RefundPolicy
}
export const weekdays = ['Thứ hai', 'Thứ ba', 'Thứ tư', 'Thứ năm', 'Thứ sáu', 'Thứ bảy', 'Chủ nhật']
export function money(value: number, currency = 'VND') {
  return new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency,
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(value)
}
