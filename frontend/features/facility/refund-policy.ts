export type RefundPolicy = {
  configured: boolean
  id: string | null
  version: number
  rateBps: number | null
  currency: string | null
  rounding: string | null
  termsHash: string | null
  createdAt: string | null
}

export function refundPercentToBps(value: string): number {
  const text = value.trim().replace(',', '.')
  if (!/^\d{1,3}(?:\.\d{1,2})?$/.test(text)) {
    throw new Error('Nhập tỷ lệ từ 0 đến 100%, tối đa 2 chữ số thập phân.')
  }
  const [whole, fraction = ''] = text.split('.')
  // At most five digits: concatenate integers instead of multiplying a decimal float.
  const bps = Number(`${whole}${fraction.padEnd(2, '0')}`)
  if (bps > 10000) throw new Error('Tỷ lệ hoàn không được vượt quá 100%.')
  return bps
}

export function refundBpsToPercent(rate: number | null): string {
  if (rate === null) return ''
  if (!Number.isInteger(rate) || rate < 0 || rate > 10000)
    throw new Error('Tỷ lệ hoàn không hợp lệ.')
  const fraction = String(rate % 100)
    .padStart(2, '0')
    .replace(/0+$/, '')
  return `${Math.floor(rate / 100)}${fraction ? `.${fraction}` : ''}`
}
