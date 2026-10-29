export type PaymentRuntime = { mode: 'REAL' | 'DEMO'; collectionCreateEnabled: boolean }
export type Bank = { bin: string; name: string; shortName: string }
export type BankCatalog = { updatedAt: string; stale: boolean; banks: Bank[] }
export type BankDestination = {
  id: string
  principalId: string | null
  guestBookingId: string | null
  bankBin: string
  bankName: string
  maskedAccountNumber: string
  accountName: string
  destinationHash: string
}
export type BankQrSession = {
  paymentId: string
  state: string
  moneyClass: 'REAL'
  amount: string
  amountReceived: string
  bankBin: string | null
  accountNumber: string | null
  accountName: string | null
  description: string
  qrCode: string | null
  checkoutUrl: string | null
  expiresAt: string
  confirmationMethod: string | null
  applicationState: string
  counterCashAllowed?: boolean
  cashPaymentId?: string | null
  lastErrorCode: string | null
}
export type PayosChannel = {
  channelId: string
  facilityId: string
  receiverRevisionId: string
  version: number
  status: string
  credentialsConfigured: boolean
  bankBin: string
  maskedAccountNumber: string | null
  accountName: string | null
  webhookUrl: string
}
