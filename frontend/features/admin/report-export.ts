import { displayLabel } from '@/lib/display-labels'
import { csvBlob, downloadBlob, xlsxBlob, type Sheet } from '@/lib/spreadsheet'
import { facilityRevenue, sportRows } from './report-partners'
import { periodLabel } from './report-range'
import { bookingFigures, provinceRows, type ReportData } from './report-sections'

const share = (part: number, whole: number) =>
  whole ? Math.round((part / whole) * 1000) / 10 : null

/** The report as tables: the same figures the page shows, numbers kept numeric. */
export function reportSheets(data: ReportData): Sheet[] {
  const now = bookingFigures(data.bookings)
  const before = data.previous ? bookingFigures(data.previous) : null
  const period = data.bookings ? periodLabel(data.bookings) : null
  const prior = data.previous ? periodLabel(data.previous) : null
  const lossRate = (f: typeof now) => share(f.lost, f.total)
  const states = data.bookings?.states ?? []
  const provinces = provinceRows(data)
  const activeFacilities = provinces.reduce((t, r) => t + r.active, 0)
  const sports = sportRows(data)
  const sportTotal = sports.reduce((t, r) => t + r.count, 0)
  const partners = facilityRevenue(data)
  const users = data.users.reduce((t, u) => t + Number(u.total), 0)
  return [
    {
      name: 'Tổng quan',
      rows: [
        ['Chỉ số', 'Kỳ này', 'Kỳ trước'],
        ['Từ ngày', period?.from ?? '', prior?.from ?? ''],
        ['Đến hết ngày', period?.to ?? '', prior?.to ?? ''],
        ['Lượt đặt mới', now.total, before?.total ?? null],
        ['Lượt đặt thành công', now.success, before?.success ?? null],
        ['Tổng doanh thu (VND, giá trị lượt đặt thành công)', now.revenue, before?.revenue ?? null],
        ['Tỷ lệ hủy hoặc hết hạn (%)', lossRate(now), before ? lossRate(before) : null],
        ['Khách chủ động hủy (lượt)', now.byCustomer, before?.byCustomer ?? null],
        ['Quá hạn chưa thanh toán (lượt)', now.expired, before?.expired ?? null],
        ['Cơ sở hoặc Admin hủy (lượt)', now.byFacility, before?.byFacility ?? null],
      ],
    },
    {
      name: 'Lượt đặt theo trạng thái',
      rows: [
        ['Trạng thái', 'Số lượt', 'Tỷ lệ (%)', 'Giá trị (VND)'],
        ...states.map((s) => [
          displayLabel(s.status),
          Number(s.count),
          share(Number(s.count), now.total),
          Number(s.booking_value),
        ]),
      ],
    },
    {
      name: 'Tài khoản theo trạng thái',
      rows: [
        ['Trạng thái (số hiện tại)', 'Số tài khoản', 'Tỷ lệ (%)'],
        ...data.users.map((u) => [
          displayLabel(u.status),
          Number(u.total),
          share(Number(u.total), users),
        ]),
      ],
    },
    {
      name: 'Cơ sở theo tỉnh thành',
      rows: [
        ['Tỉnh/thành (số hiện tại)', 'Đang hoạt động', 'Tổng cơ sở', 'Tỷ trọng đang hoạt động (%)'],
        ...provinces.map((r) => [r.name, r.active, r.total, share(r.active, activeFacilities)]),
      ],
    },
    {
      name: 'Lượt đặt theo môn',
      rows: [
        ['Môn thể thao', 'Số lượt', 'Tỷ lệ (%)'],
        ...sports.map((r) => [r.sport, r.count, share(r.count, sportTotal)]),
      ],
    },
    {
      name: 'Doanh thu theo cơ sở',
      rows: [
        ['Cơ sở', 'Địa chỉ', 'Doanh thu (VND)', 'Số lượt', 'Tỷ trọng (%)'],
        ...partners.map((r) => [
          r.name,
          r.address,
          r.revenue,
          r.count,
          share(r.revenue, now.revenue),
        ]),
      ],
    },
  ]
}

export function exportReport(data: ReportData, format: 'xlsx' | 'csv') {
  const sheets = reportSheets(data)
  const period = data.bookings ? periodLabel(data.bookings) : null
  const suffix = period ? `${period.from}-${period.to}`.replaceAll('/', '') : 'hien-tai'
  downloadBlob(
    format === 'xlsx' ? xlsxBlob(sheets) : csvBlob(sheets),
    `sporthub-bao-cao-${suffix}.${format}`,
  )
}
