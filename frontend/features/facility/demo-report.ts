export type ReportMoney = {
  courtCollected: string
  transferFees: string
  operatingRevenue: string
  policyRefunded: string
  afterRefund: string
  transferCollected: string
  sellerPaid: string
  sellerDue: string
  refundDue: string
  technicalRefunded: string
  bankCollected?: string
  unappliedCollected?: string
}
export type ReportDay = {
  date: string
  courtCollected: string
  transferFees: string
  policyRefunded: string
}
export type ReportCourt = {
  courtId: string
  courtName: string
  bookingCount: number
  fullyPaidBookingCount: number
  bookedMinutes: string
  usedMinutes: string
  cancelledCount: number
  expiredCount: number
  money: ReportMoney
}
export type ReportTransaction = {
  id: string
  kind: string
  direction: string
  revenueComponent: boolean
  cashflow: boolean
  countsTowardTotals: boolean
  bookingId: string
  courtId: string
  courtName: string
  source: string
  purpose: string
  state: string
  amount: string
  currency: string
  effectiveAt: string
  moneyClass?: string
  confirmationMethod?: string
  dateBasis?: string
}
export type FacilityDemoReport = {
  peer?: PeerReport
  facilityId: string
  mode: 'DEMO' | 'REAL'
  label: string
  asOf: string
  obligationCutoff: string
  dateBasis?: Record<string, string>
  window: {
    fromDate: string
    toDate: string
    from: string
    to: string
    timezone: string
    interval: string
  }
  moneyAvailability: 'COMPLETE' | 'PARTIAL' | 'UNAVAILABLE'
  reasons: string[]
  summary: {
    bookingCount: number
    fullyPaidBookingCount: number
    bookedMinutes: string
    usedMinutes: string
    cancelledCount: number
    expiredCount: number
    money: ReportMoney
  }
  daily: ReportDay[]
  courts: ReportCourt[]
  transactions: {
    page: number
    size: number
    total: number
    totalPages: number
    items: ReportTransaction[]
  }
}
export type PeerReport = {
  availability: 'COMPLETE' | 'PARTIAL' | 'UNAVAILABLE' | 'NOT_APPLICABLE'
  reasons: string[]
  dateBasis: string
  completedUsageCount?: number
  openUsageCases: number
  openMoneyCases: number
  buyerDeclaredAmount: string
  sellerAcknowledgedAmount: string
  ownerAwardedAmount: string
  returnDeclaredAmount: string
  returnAcknowledgedAmount: string
  transactions: {
    page: number
    size: number
    total: number
    totalPages: number
    items: {
      acquisitionId: string
      bookingId: string
      courtId: string
      payerId: string
      recipientId: string
      protocol: 'PEER_RESALE_V1'
      amount: string
      phase: string
      confirmationMethod: string
      usageDecision: string | null
      moneyState: string | null
      createdAt: string
      declaredAt: string | null
      decidedAt: string | null
    }[]
  }
}
export type ReportRange = { fromDate: string; toDate: string }
export function facilityCalendarDate(timezone: string, now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}
export function reportPreset(
  timezone: string,
  preset: '7' | '30' | 'month',
  now = new Date(),
): ReportRange {
  const toDate = facilityCalendarDate(timezone, now)
  const from = new Date(`${toDate}T00:00:00Z`)
  if (preset === 'month') from.setUTCDate(1)
  else from.setUTCDate(from.getUTCDate() - (Number(preset) - 1))
  return { fromDate: from.toISOString().slice(0, 10), toDate }
}
export function validateReportRange(range: ReportRange): string {
  const valid = (value: string) =>
    /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    Number.isFinite(Date.parse(`${value}T00:00:00Z`)) &&
    new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
  if (!valid(range.fromDate) || !valid(range.toDate))
    return 'Chọn đủ ngày bắt đầu và ngày kết thúc hợp lệ.'
  const days =
    (Date.parse(`${range.toDate}T00:00:00Z`) - Date.parse(`${range.fromDate}T00:00:00Z`)) /
      86400000 +
    1
  if (days < 1) return 'Ngày kết thúc phải từ ngày bắt đầu trở đi.'
  if (days > 366) return 'Mỗi kỳ báo cáo tối đa 366 ngày.'
  return ''
}
export function demoVnd(value: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(value)) return '—'
  const [integer, fraction = ''] = value.split('.')
  const decimals = fraction.replace(/0+$/, '')
  return `${integer.replace(/\B(?=(\d{3})+(?!\d))/g, '.')}${decimals ? `,${decimals}` : ''} ₫`
}
export const reportKindLabels: Record<string, string> = {
  BANK_CREDIT: 'Tiền ngân hàng thực nhận',
  COURT_COLLECTION: 'Thu tiền sân',
  TRANSFER_COLLECTION: 'Người mua thanh toán chuyển nhượng',
  FACILITY_TRANSFER_FEE: 'Phí chuyển nhượng cơ sở hưởng',
  SELLER_PAYOUT: 'Trả người chuyển nhượng',
  POLICY_REFUND: 'Hoàn theo chính sách',
  TECHNICAL_REFUND: 'Hoàn do bàn giao thất bại',
}
export function calendarLabel(value: string): string {
  return value.split('-').reverse().join('/')
}
