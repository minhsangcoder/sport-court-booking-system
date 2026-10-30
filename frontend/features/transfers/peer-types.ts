export type PeerAttempt = {
  id: string
  listingId: string
  bookingId: string
  sellerId: string
  buyerId: string
  protocol: 'PEER_RESALE_V1'
  phase: string
  version: number
  amount: number | string
  currency: string
  payBy: string
  usageDecisionBy: string
  conversationId: string
  termsHash: string
  confirmationMethod: string
  decision: { decision: string; reason: string; decidedAt: string } | null
}
export const peerPhases: Record<string, string> = {
  WAITING_PAYMENT: 'Chờ người mua chuyển tiền',
  PAYMENT_DECLARED: 'Người mua báo đã chuyển tiền',
  DISPUTED: 'Sân tạm khóa theo hồ sơ cũ',
  HANDOFF_PENDING: 'Đang bàn giao',
  COMPLETED: 'Đã bàn giao cho người mua',
  EXPIRED: 'Lượt giữ đã kết thúc',
  ABORTED_A_RETAINS: 'Người bán giữ quyền sân',
  TIMED_OUT_A_RETAINS: 'Đã đến giờ chơi; người bán giữ sân',
}
export const finalPeerPhase = (phase: string) =>
  ['COMPLETED', 'EXPIRED', 'ABORTED_A_RETAINS', 'TIMED_OUT_A_RETAINS'].includes(phase)
export type PeerMessage = {
  id: string
  cursor: number
  senderId: string
  text: string
  createdAt: string
}
