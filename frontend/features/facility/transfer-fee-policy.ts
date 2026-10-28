export type TransferFeePolicy = {
  configured: boolean
  id: string | null
  version: number
  feeBps: number | null
  currency: string | null
  rounding: string | null
  termsHash: string | null
  createdAt: string | null
}

export function transferFeePercentToBps(value: string): number {
  const text = value.trim().replace(',', '.')
  if (!/^\d{1,3}(?:\.\d{1,2})?$/.test(text))
    throw new Error('Nhập phí từ 0 đến 100%, tối đa 2 chữ số thập phân.')
  const [whole, fraction = ''] = text.split('.')
  const result = Number(`${whole}${fraction.padEnd(2, '0')}`)
  if (result > 10000) throw new Error('Phí chuyển nhượng không được vượt quá 100%.')
  return result
}

export function transferFeeBpsToPercent(value: number | null): string {
  if (value === null) return ''
  if (!Number.isInteger(value) || value < 0 || value > 10000)
    throw new Error('Phí chuyển nhượng không hợp lệ.')
  const decimals = String(value % 100)
    .padStart(2, '0')
    .replace(/0+$/, '')
  return `${Math.floor(value / 100)}${decimals ? `.${decimals}` : ''}`
}
