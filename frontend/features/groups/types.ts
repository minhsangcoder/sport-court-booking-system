import type { Booking } from '@/features/booking/types'
export type Member={id:string;userId:string;displayName:string;active:boolean;amountDue:number;amountPaid:number;paymentState:string;paymentRequestedAt:string|null;lastReminderAt:string|null;nextReminderAt:string|null;reminderEligible:boolean;reminderBlockedReason:string|null}
export type ReminderResult={acceptedCount:number;skippedCount:number;members:{memberId:string;outcome:'ACCEPTED'|'SKIPPED';reason:string|null;lastReminderAt:string|null;nextReminderAt:string|null}[]}
export type Group={id:string;ownerId:string;name:string;state:string;deadline:string;maxMembers:number;allocationsLocked:boolean;booking:Booking;members:Member[];totalPaid:number}
export type Invite={code:string;path:string;qrSvg:string;qrPngDataUrl?:string;expiresAt:string}
export const groupStates:Record<string,string>={GROUP_PENDING:'Đang nhận đóng góp',CONFIRMED:'Đã xác nhận',GROUP_EXPIRED:'Hết hạn thanh toán',GROUP_CANCELLED:'Đã hủy'}
export const memberStates:Record<string,string>={UNPAID:'Chờ chia tiền',WAITING_FOR_PAYMENT:'Chờ đóng góp',PAID:'Đã đóng',PAID_BY_OWNER:'Người tổ chức đã trả thay'}
