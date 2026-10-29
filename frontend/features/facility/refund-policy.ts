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
